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
  it.each(['LIVE', 'AGING'] as const)(
    'buckets %s occupancy on availableSpaces, as before',
    (freshness) => {
      const fresh = { freshness, availabilityFreshness: freshness };
      expect(municipalAvailabilityBucket(facility({ ...fresh, availableSpaces: 12 }))).toBe(
        'available',
      );
      expect(municipalAvailabilityBucket(facility({ ...fresh, availableSpaces: 0 }))).toBe(
        'unavailable',
      );
      expect(municipalAvailabilityBucket(facility({ ...fresh, availableSpaces: null }))).toBe(
        'unknown',
      );
    },
  );

  it('puts a stale facility with a non-zero availableSpaces in unknown', () => {
    // The card shows the stale copy, not the retained number (#598).
    expect(
      municipalAvailabilityBucket(
        facility({ availableSpaces: 17, freshness: 'STALE', availabilityFreshness: 'STALE' }),
      ),
    ).toBe('unknown');
  });

  it.each([
    { freshness: 'STALE', availabilityFreshness: null },
    { freshness: 'UNAVAILABLE', availabilityFreshness: 'UNAVAILABLE' },
    { freshness: 'INVALID', availabilityFreshness: 'INVALID' },
    { freshness: null, availabilityFreshness: null },
  ] as const)(
    'puts numbers with freshness $freshness / $availabilityFreshness in unknown',
    (freshness) => {
      expect(municipalAvailabilityBucket(facility({ ...freshness, availableSpaces: 9 }))).toBe(
        'unknown',
      );
      expect(municipalAvailabilityBucket(facility({ ...freshness, availableSpaces: 0 }))).toBe(
        'unknown',
      );
    },
  );

  it('reads freshness with the same precedence as the card', () => {
    // availabilityFreshness wins over the facility-level freshness.
    expect(
      municipalAvailabilityBucket(
        facility({ availableSpaces: 9, freshness: 'LIVE', availabilityFreshness: 'STALE' }),
      ),
    ).toBe('unknown');
    expect(
      municipalAvailabilityBucket(
        facility({ availableSpaces: 9, freshness: 'STALE', availabilityFreshness: 'AGING' }),
      ),
    ).toBe('available');
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
      freshness: 'LIVE',
      availabilityFreshness: 'LIVE',
      sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
      facilityType: 'OFF_STREET',
      selectedFieldProvenanceSummary: { NAME: 'izum' },
    }),
    facility({
      id: 'full-izum',
      availableSpaces: 0,
      freshness: 'LIVE',
      availabilityFreshness: 'LIVE',
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

  it('filters stale retained numbers as unknown, not available or unavailable', () => {
    const withStale = [
      ...facilities,
      facility({
        id: 'stale-izum',
        availableSpaces: 5,
        freshness: 'STALE',
        availabilityFreshness: 'STALE',
        sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
      }),
      facility({
        id: 'stale-full-izum',
        availableSpaces: 0,
        freshness: 'STALE',
        availabilityFreshness: 'STALE',
        sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
      }),
    ];
    const ids = (availability: 'available' | 'unavailable' | 'unknown') =>
      filterMunicipalFacilities(withStale, { ...EMPTY_MUNICIPAL_FILTERS, availability }).map(
        (f) => f.id,
      );

    expect(ids('available')).toEqual(['avail-izum']);
    expect(ids('unavailable')).toEqual(['full-izum']);
    expect(ids('unknown')).toEqual(['osm-unknown', 'stale-izum', 'stale-full-izum']);
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
