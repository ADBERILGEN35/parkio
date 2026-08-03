import type { MunicipalFacility } from '@parkio/types';

/** Fixture matching `MunicipalFacilityResponse` for WEB-MUNI-01 tests. */
export function makeMunicipalFacility(
  overrides: Partial<MunicipalFacility> &
    Pick<MunicipalFacility, 'id' | 'latitude' | 'longitude'> = {
    id: '70db58f2-4cca-4010-9315-fa46b30fba1e',
    latitude: 38.4237,
    longitude: 27.1428,
  },
): MunicipalFacility {
  return {
    displayName: 'Konak Otopark',
    operatorName: null,
    facilityType: 'OFF_STREET',
    addressText: 'Konak, İzmir',
    capacityTotal: 120,
    availableSpaces: null,
    freshness: 'UNAVAILABLE',
    attribution: 'OpenStreetMap contributors',
    sourceLabel: 'OSM',
    lastUpdatedAt: '2026-08-03T12:00:00Z',
    contributingSourceKeys: ['osm-geofabrik-turkey'],
    selectedFieldProvenanceSummary: { displayName: 'osm-geofabrik-turkey' },
    registryConfidenceOrReviewStatus: null,
    availabilitySource: null,
    availabilityFreshness: 'UNAVAILABLE',
    availabilityObservationTimestamp: null,
    ...overrides,
  };
}
