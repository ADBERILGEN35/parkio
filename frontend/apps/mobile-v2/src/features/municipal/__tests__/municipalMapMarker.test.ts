import type { MunicipalFacility } from '@parkio/types';
import { toMapMunicipalMarker, toMapMunicipalMarkers } from '../municipalMapMarker';

const labelOptions = {
  unnamedLabel: 'Municipal parking',
  occupancyLabels: {
    live: 'Live',
    aging: 'Live',
    stale_live: 'Out of date',
    static: 'Static information',
    invalid: 'Invalid data',
  },
} as const;

function makeFacility(overrides: Partial<MunicipalFacility> = {}): MunicipalFacility {
  return {
    id: '70db58f2-4cca-4010-9315-fa46b30fba1e',
    displayName: 'Test Facility',
    operatorName: null,
    facilityType: 'OFF_STREET',
    addressText: 'İzmir',
    latitude: 38.4237,
    longitude: 27.1428,
    capacityTotal: 100,
    availableSpaces: null,
    occupiedSpaces: null,
    freshness: 'UNAVAILABLE',
    attribution: 'OpenStreetMap contributors',
    sourceLabel: 'OpenStreetMap',
    lastUpdatedAt: '2026-08-05T12:00:00Z',
    contributingSourceKeys: ['osm-geofabrik-turkey'],
    selectedFieldProvenanceSummary: null,
    registryConfidenceOrReviewStatus: null,
    availabilitySource: null,
    availabilityFreshness: 'UNAVAILABLE',
    availabilityObservationTimestamp: null,
    ...overrides,
  };
}

describe('toMapMunicipalMarkers', () => {
  it('maps live İZUM facilities', () => {
    const marker = toMapMunicipalMarker(
      makeFacility({
        availableSpaces: 3,
        occupiedSpaces: 10,
        capacityTotal: 13,
        freshness: 'LIVE',
        availabilityFreshness: 'LIVE',
        contributingSourceKeys: ['izmir-izum-otoparklar'],
      }),
      labelOptions,
    );
    expect(marker).toMatchObject({
      id: '70db58f2-4cca-4010-9315-fa46b30fba1e',
      occupancyKind: 'live',
      lat: 38.4237,
      lng: 27.1428,
    });
    expect(marker?.accessibilityLabel).toContain('Live');
    expect(marker?.accessibilityLabel).toContain('İzmir Büyükşehir Belediyesi / İZUM');
    expect(marker?.accessibilityLabel).not.toContain('izmir-izum');
  });

  it('maps stale İZUM as stale_live', () => {
    const marker = toMapMunicipalMarker(
      makeFacility({
        freshness: 'STALE',
        availabilityFreshness: 'STALE',
        contributingSourceKeys: ['izmir-izum-otoparklar'],
      }),
      labelOptions,
    );
    expect(marker?.occupancyKind).toBe('stale_live');
  });

  it('maps OSM as static', () => {
    expect(toMapMunicipalMarker(makeFacility(), labelOptions)?.occupancyKind).toBe('static');
  });

  it('filters invalid coordinates', () => {
    expect(
      toMapMunicipalMarker(makeFacility({ latitude: Number.NaN }), labelOptions),
    ).toBeNull();
    expect(
      toMapMunicipalMarkers([makeFacility({ longitude: Number.POSITIVE_INFINITY })], labelOptions),
    ).toEqual([]);
  });

  it('deduplicates facility ids (first wins)', () => {
    const first = makeFacility({ displayName: 'First', availableSpaces: 1, freshness: 'LIVE' });
    const second = makeFacility({
      displayName: 'Second',
      availableSpaces: 9,
      freshness: 'LIVE',
      contributingSourceKeys: ['izmir-izum-otoparklar'],
    });
    const markers = toMapMunicipalMarkers([first, second], labelOptions);
    expect(markers).toHaveLength(1);
    expect(markers[0]?.accessibilityLabel).toContain('First');
  });

  it('keeps municipal marker ids as facility UUIDs without spot-model fields', () => {
    const marker = toMapMunicipalMarker(makeFacility(), labelOptions);
    expect(marker).not.toHaveProperty('createdAt');
    expect(marker).not.toHaveProperty('expiresAt');
    expect(marker).not.toHaveProperty('live');
    expect(JSON.stringify(marker)).not.toContain('osm-geofabrik-turkey');
  });
});
