import type { MunicipalFacility } from '@parkio/types';
import { DEFAULT_MUNICIPAL_MAP_FILTERS } from '../municipalFilterModel';
import {
  applyMunicipalMapFilters,
  facilityMatchesMunicipalFilters,
  matchesMunicipalOccupancyFilter,
  matchesMunicipalSourceFilter,
} from '../municipalFilterPipeline';

function facility(overrides: Partial<MunicipalFacility> = {}): MunicipalFacility {
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
    sourceLabel: 'OpenStreetMap contributors / Geofabrik GmbH',
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

const osm = facility({ id: 'osm-1' });
const izumLive = facility({
  id: 'izum-live',
  availableSpaces: 0,
  occupiedSpaces: 40,
  capacityTotal: 40,
  freshness: 'LIVE',
  availabilityFreshness: 'LIVE',
  availabilitySource: 'izmir-izum-otoparklar',
  contributingSourceKeys: ['izmir-izum-otoparklar'],
  sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
});
const izumAging = facility({
  id: 'izum-aging',
  availableSpaces: 5,
  occupiedSpaces: 10,
  capacityTotal: 15,
  freshness: 'AGING',
  availabilityFreshness: 'AGING',
  contributingSourceKeys: ['izmir-izum-otoparklar'],
  sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
});
const izumStale = facility({
  id: 'izum-stale',
  availableSpaces: null,
  freshness: 'STALE',
  availabilityFreshness: 'STALE',
  contributingSourceKeys: ['izmir-izum-otoparklar'],
  sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
});
const dual = facility({
  id: 'dual',
  contributingSourceKeys: ['izmir-izum-otoparklar', 'osm-geofabrik-turkey'],
  sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
  availableSpaces: 2,
  freshness: 'LIVE',
  availabilityFreshness: 'LIVE',
});

describe('municipalFilterPipeline', () => {
  it('does not mutate the input array', () => {
    const input = [osm, izumLive];
    const frozen = Object.freeze([...input]);
    const result = applyMunicipalMapFilters(frozen, {
      ...DEFAULT_MUNICIPAL_MAP_FILTERS,
      source: 'osm',
    });
    expect(input).toHaveLength(2);
    expect(result.facilities).toHaveLength(1);
    expect(result.facilities[0]?.id).toBe('osm-1');
  });

  it('classifies source with typed labels, not display-string equality on raw keys', () => {
    expect(matchesMunicipalSourceFilter(izumLive, 'izum')).toBe(true);
    expect(matchesMunicipalSourceFilter(izumLive, 'osm')).toBe(false);
    expect(matchesMunicipalSourceFilter(osm, 'osm')).toBe(true);
    expect(matchesMunicipalSourceFilter(dual, 'izum')).toBe(true);
    expect(matchesMunicipalSourceFilter(dual, 'osm')).toBe(true);
  });

  it('keeps zero-available live facilities under live occupancy', () => {
    expect(matchesMunicipalOccupancyFilter(izumLive, 'live')).toBe(true);
    expect(izumLive.availableSpaces).toBe(0);
  });

  it('includes aging under live and excludes stale-live from live and static', () => {
    expect(matchesMunicipalOccupancyFilter(izumAging, 'live')).toBe(true);
    expect(matchesMunicipalOccupancyFilter(izumStale, 'live')).toBe(false);
    expect(matchesMunicipalOccupancyFilter(izumStale, 'static')).toBe(false);
    expect(matchesMunicipalOccupancyFilter(izumStale, 'all')).toBe(true);
    expect(matchesMunicipalOccupancyFilter(osm, 'static')).toBe(true);
  });

  it('combines source and occupancy filters', () => {
    expect(
      facilityMatchesMunicipalFilters(izumLive, { source: 'izum', occupancy: 'live' }),
    ).toBe(true);
    expect(
      facilityMatchesMunicipalFilters(izumStale, { source: 'izum', occupancy: 'live' }),
    ).toBe(false);
    expect(facilityMatchesMunicipalFilters(osm, { source: 'osm', occupancy: 'static' })).toBe(
      true,
    );
  });

  it('summarizes live, static, and stale separately', () => {
    const result = applyMunicipalMapFilters(
      [osm, izumLive, izumAging, izumStale],
      DEFAULT_MUNICIPAL_MAP_FILTERS,
    );
    expect(result.summary).toEqual({
      total: 4,
      live: 2,
      staticOnly: 1,
      staleLive: 1,
    });
    expect(result.emptyReason).toBeNull();
  });

  it('reports filtered empty when raw results exist but filters remove all', () => {
    const result = applyMunicipalMapFilters([osm, izumStale], {
      ...DEFAULT_MUNICIPAL_MAP_FILTERS,
      occupancy: 'live',
    });
    expect(result.facilities).toHaveLength(0);
    expect(result.emptyReason).toBe('filtered');
  });

  it('reports none_nearby when the query set is empty', () => {
    const result = applyMunicipalMapFilters([], DEFAULT_MUNICIPAL_MAP_FILTERS);
    expect(result.emptyReason).toBe('none_nearby');
  });

  it('clears facilities when the layer is off', () => {
    const result = applyMunicipalMapFilters([osm, izumLive], {
      ...DEFAULT_MUNICIPAL_MAP_FILTERS,
      layerEnabled: false,
    });
    expect(result.facilities).toHaveLength(0);
    expect(result.selectedFacilityValid).toBe(false);
  });

  it('marks selection invalid when filtered out', () => {
    const result = applyMunicipalMapFilters([osm, izumLive], {
      ...DEFAULT_MUNICIPAL_MAP_FILTERS,
      source: 'osm',
    }, { selectedId: 'izum-live' });
    expect(result.selectedFacilityValid).toBe(false);
    expect(result.facilities.map((f) => f.id)).toEqual(['osm-1']);
  });

  it('flags result-limit honesty when the page is full', () => {
    const many = Array.from({ length: 50 }, (_, i) => facility({ id: `f-${i}` }));
    const result = applyMunicipalMapFilters(many, DEFAULT_MUNICIPAL_MAP_FILTERS, {
      resultLimit: 50,
    });
    expect(result.resultLimitReached).toBe(true);
    expect(result.summary.total).toBe(50);
  });
});
