import type { PublicSpot } from '@parkio/types';

/**
 * Real-shaped {@link PublicSpot} fixtures for map-feature tests. Every field
 * mirrors the backend DTO; presentation (availability/tone/confidence) is derived
 * from `status` by the shared `presentSpot`, so tests never fabricate those.
 */
export function makeSpot(overrides: Partial<PublicSpot> = {}): PublicSpot {
  return {
    id: 'spot-1',
    mediaId: 'media-1',
    latitude: 38.4187,
    longitude: 27.1283,
    addressText: 'Konak Meydanı, İzmir',
    description: null,
    manualLocationEdited: false,
    suitableVehicleTypes: ['SEDAN'],
    parkingContext: 'STREET_PARKING',
    legalStatus: 'LEGAL',
    violationReasons: [],
    status: 'ACTIVE',
    expiresAt: '2026-07-01T12:00:00Z',
    createdAt: '2026-06-29T12:00:00Z',
    updatedAt: '2026-06-29T12:00:00Z',
    ...overrides,
  };
}
