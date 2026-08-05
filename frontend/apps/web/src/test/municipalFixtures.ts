import type { MunicipalFacility } from '@parkio/types';

/** Fixture matching `MunicipalFacilityResponse` for WEB-MUNI tests. */
export function makeMunicipalFacility(
  overrides: Partial<MunicipalFacility> = {},
): MunicipalFacility {
  return {
    id: '70db58f2-4cca-4010-9315-fa46b30fba1e',
    displayName: 'Konak Otopark',
    operatorName: null,
    facilityType: 'OFF_STREET',
    addressText: 'Konak, İzmir',
    latitude: 38.4237,
    longitude: 27.1428,
    capacityTotal: 120,
    availableSpaces: null,
    freshness: 'UNAVAILABLE',
    attribution: 'OpenStreetMap contributors',
    sourceLabel: 'OpenStreetMap contributors / Geofabrik GmbH',
    lastUpdatedAt: '2026-08-03T12:00:00Z',
    contributingSourceKeys: ['osm-geofabrik-turkey'],
    selectedFieldProvenanceSummary: {
      ATTRIBUTION: 'osm-geofabrik-turkey',
      COORDINATES: 'osm-geofabrik-turkey',
    },
    registryConfidenceOrReviewStatus: null,
    availabilitySource: null,
    availabilityFreshness: 'UNAVAILABLE',
    availabilityObservationTimestamp: null,
    ...overrides,
  };
}
