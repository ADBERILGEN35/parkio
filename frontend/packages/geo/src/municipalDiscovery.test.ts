import { describe, expect, it } from 'vitest';
import type { MunicipalFacility } from '@parkio/types';
import {
  EMPTY_MUNICIPAL_FILTERS,
  availableMunicipalFacilityTypes,
  availableMunicipalSourceLabels,
  filterMunicipalFacilities,
  hasActiveMunicipalFilters,
  hasMunicipalProvenance,
  municipalAvailabilityBucket,
} from './municipalDiscovery';

function facility(overrides: Partial<MunicipalFacility> = {}): MunicipalFacility {
  return {
    id: overrides.id ?? 'id-1',
    displayName: 'Lot',
    operatorName: null,
    facilityType: 'OFF_STREET',
    addressText: null,
    latitude: 38.42,
    longitude: 27.14,
    capacityTotal: 100,
    availableSpaces: null,
    occupiedSpaces: null,
    freshness: 'UNAVAILABLE',
    attribution: 'OpenStreetMap contributors',
    sourceLabel: 'OpenStreetMap contributors / Geofabrik GmbH',
    lastUpdatedAt: null,
    contributingSourceKeys: null,
    selectedFieldProvenanceSummary: { COORDINATES: 'osm-geofabrik-turkey' },
    registryConfidenceOrReviewStatus: null,
    availabilitySource: null,
    availabilityFreshness: 'UNAVAILABLE',
    availabilityObservationTimestamp: null,
    ...overrides,
  };
}

describe('municipalAvailabilityBucket', () => {
  it('classifies only from availableSpaces', () => {
    expect(municipalAvailabilityBucket(facility({ availableSpaces: 12 }))).toBe('available');
    expect(municipalAvailabilityBucket(facility({ availableSpaces: 0 }))).toBe('unavailable');
    expect(municipalAvailabilityBucket(facility({ availableSpaces: null }))).toBe('unknown');
  });

  it('does not infer from capacity or freshness alone', () => {
    expect(
      municipalAvailabilityBucket(
        facility({
          availableSpaces: null,
          capacityTotal: 200,
          freshness: 'LIVE',
          availabilityFreshness: 'LIVE',
        }),
      ),
    ).toBe('unknown');
  });
});

describe('hasMunicipalProvenance', () => {
  it('requires a non-empty selectedFieldProvenanceSummary', () => {
    expect(hasMunicipalProvenance(facility())).toBe(true);
    expect(hasMunicipalProvenance(facility({ selectedFieldProvenanceSummary: null }))).toBe(false);
    expect(hasMunicipalProvenance(facility({ selectedFieldProvenanceSummary: {} }))).toBe(false);
  });
});

describe('availableMunicipalSourceLabels / types', () => {
  it('lists distinct source labels from the payload only', () => {
    const labels = availableMunicipalSourceLabels([
      facility({ sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM' }),
      facility({ sourceLabel: 'OpenStreetMap contributors / Geofabrik GmbH' }),
      facility({ sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM' }),
      facility({ sourceLabel: null }),
      facility({ sourceLabel: '  ' }),
    ]);
    expect(labels).toEqual([
      'Izmir Buyuksehir Belediyesi / IZUM',
      'OpenStreetMap contributors / Geofabrik GmbH',
    ]);
  });

  it('lists facility types in canonical order', () => {
    expect(
      availableMunicipalFacilityTypes([
        facility({ facilityType: 'UNKNOWN' }),
        facility({ facilityType: 'ON_STREET' }),
        facility({ facilityType: 'OFF_STREET' }),
      ]),
    ).toEqual(['ON_STREET', 'OFF_STREET', 'UNKNOWN']);
  });
});

describe('filterMunicipalFacilities', () => {
  const facilities = [
    facility({
      id: 'avail-izum',
      availableSpaces: 8,
      sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
      facilityType: 'OFF_STREET',
      selectedFieldProvenanceSummary: { NAME: 'izum' },
    }),
    facility({
      id: 'full-izum',
      availableSpaces: 0,
      sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
      facilityType: 'ON_STREET',
      selectedFieldProvenanceSummary: null,
    }),
    facility({
      id: 'osm-unknown',
      availableSpaces: null,
      sourceLabel: 'OpenStreetMap contributors / Geofabrik GmbH',
      facilityType: 'UNKNOWN',
      selectedFieldProvenanceSummary: { COORDINATES: 'osm' },
    }),
  ];

  it('returns all when filters are empty', () => {
    expect(filterMunicipalFacilities(facilities, EMPTY_MUNICIPAL_FILTERS)).toHaveLength(3);
    expect(hasActiveMunicipalFilters(EMPTY_MUNICIPAL_FILTERS)).toBe(false);
  });

  it('filters by availability', () => {
    expect(
      filterMunicipalFacilities(facilities, {
        ...EMPTY_MUNICIPAL_FILTERS,
        availability: 'available',
      }).map((f) => f.id),
    ).toEqual(['avail-izum']);
    expect(
      filterMunicipalFacilities(facilities, {
        ...EMPTY_MUNICIPAL_FILTERS,
        availability: 'unavailable',
      }).map((f) => f.id),
    ).toEqual(['full-izum']);
    expect(
      filterMunicipalFacilities(facilities, {
        ...EMPTY_MUNICIPAL_FILTERS,
        availability: 'unknown',
      }).map((f) => f.id),
    ).toEqual(['osm-unknown']);
  });

  it('filters by exact sourceLabel', () => {
    expect(
      filterMunicipalFacilities(facilities, {
        ...EMPTY_MUNICIPAL_FILTERS,
        sourceLabels: ['OpenStreetMap contributors / Geofabrik GmbH'],
      }).map((f) => f.id),
    ).toEqual(['osm-unknown']);
  });

  it('filters by facility type', () => {
    expect(
      filterMunicipalFacilities(facilities, {
        ...EMPTY_MUNICIPAL_FILTERS,
        facilityTypes: ['ON_STREET', 'UNKNOWN'],
      }).map((f) => f.id),
    ).toEqual(['full-izum', 'osm-unknown']);
  });

  it('filters by provenance presence', () => {
    expect(
      filterMunicipalFacilities(facilities, {
        ...EMPTY_MUNICIPAL_FILTERS,
        provenanceOnly: true,
      }).map((f) => f.id),
    ).toEqual(['avail-izum', 'osm-unknown']);
  });

  it('combines filters with AND semantics', () => {
    expect(
      filterMunicipalFacilities(facilities, {
        availability: 'available',
        sourceLabels: ['Izmir Buyuksehir Belediyesi / IZUM'],
        facilityTypes: ['OFF_STREET'],
        provenanceOnly: true,
      }).map((f) => f.id),
    ).toEqual(['avail-izum']);
  });
});
