import type { SpotCreationDraft } from '../../state/spotCreationDraftStore';
import { buildCreateSpotRequest } from '../createSpotRequest';
import { isGpsAccuracyAcceptable } from '../locationAccuracy';

function draft(overrides: Partial<SpotCreationDraft> = {}): SpotCreationDraft {
  return {
    media: {
      mediaId: '11111111-1111-4111-8111-111111111111',
      status: 'READY',
      contentType: 'image/jpeg',
      fileSize: 1234,
    },
    previewUri: 'file:///spot.jpg',
    location: { lat: 41.01, lng: 29.02 },
    gpsAccuracyMeters: 18,
    manualLocationEdited: false,
    vehicleType: 'SEDAN',
    parkingContext: 'STREET_PARKING',
    note: '',
    submitIdempotencyKey: null,
    ...overrides,
  };
}

describe('buildCreateSpotRequest', () => {
  it('builds the backend DTO with uploaded media, location, type and optional note', () => {
    const result = buildCreateSpotRequest(
      draft({
        note: 'Near the side entrance',
        manualLocationEdited: true,
        vehicleType: 'SUV',
        parkingContext: 'OPEN_PARKING_LOT',
      }),
    );

    expect(result.error).toBeUndefined();
    expect(result.request).toMatchObject({
      mediaId: '11111111-1111-4111-8111-111111111111',
      latitude: 41.01,
      longitude: 29.02,
      description: 'Near the side entrance',
      manualLocationEdited: true,
      suitableVehicleTypes: ['SUV'],
      parkingContext: 'OPEN_PARKING_LOT',
      legalStatus: 'LEGAL',
      violationReasons: [],
    });
  });

  it('blocks submit when GPS has not been acquired', () => {
    expect(buildCreateSpotRequest(draft({ location: null })).error).toMatch(/gps location/i);
  });

  it('blocks submit when GPS accuracy is too low', () => {
    expect(buildCreateSpotRequest(draft({ gpsAccuracyMeters: 120 })).error).toMatch(/accuracy/i);
  });

  it('reuses the shared validation schema for backend validation errors', () => {
    expect(buildCreateSpotRequest(draft({ note: 'x'.repeat(1001) })).error).toMatch(/1000/);
  });
});

describe('isGpsAccuracyAcceptable', () => {
  it('requires a positive fix within the creation threshold', () => {
    expect(isGpsAccuracyAcceptable(75)).toBe(true);
    expect(isGpsAccuracyAcceptable(76)).toBe(false);
    expect(isGpsAccuracyAcceptable(null)).toBe(false);
    expect(isGpsAccuracyAcceptable(0)).toBe(false);
  });
});
