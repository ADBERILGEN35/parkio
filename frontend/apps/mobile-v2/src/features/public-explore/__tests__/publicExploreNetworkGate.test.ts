import type { PublicExploreDiscovery } from '@parkio/types';
import { PUBLIC_EXPLORE_LIMIT, PUBLIC_EXPLORE_RADIUS_METERS } from '../constants';
import { toRenderablePublicFacilities } from '../renderablePublicFacilities';
import { selectPublicFacilityForPreview } from '../selectPublicFacilityForPreview';

/**
 * Network / contract hard-gate tests — pure module level so we do not mount
 * the full Expo Router tree. Verifies Wave-1 privacy invariants.
 */

const mockList = jest.fn();

jest.mock('@/services/api', () => ({
  publicExploreApi: {
    list: (...args: unknown[]) => mockList(...args),
  },
  parkingApi: {
    getNearbySpots: jest.fn(),
    getNearbyMunicipalFacilities: jest.fn(),
    getMunicipalFacility: jest.fn(),
    getSpot: jest.fn(),
  },
  geocodingApi: {
    searchPlaces: jest.fn(),
  },
}));

import { publicExploreQueryOptions } from '@/data/query-options/publicExplore';
import { parkingApi, geocodingApi } from '@/services/api';

const sampleEnvelope: PublicExploreDiscovery = {
  facilities: [
    {
      id: 'f1',
      displayName: 'A',
      operatorName: null,
      facilityType: 'OFF_STREET',
      addressText: null,
      latitude: 38.42,
      longitude: 27.14,
      capacityTotal: 10,
      availableSpaces: 2,
      availabilityFreshness: 'LIVE',
      dataUpdatedAt: null,
      sourceLabel: 'OpenStreetMap',
      attribution: 'OSM',
    },
    {
      id: 'bad',
      displayName: 'Bad',
      operatorName: null,
      facilityType: 'OFF_STREET',
      addressText: null,
      latitude: Number.NaN,
      longitude: 27.14,
      capacityTotal: null,
      availableSpaces: null,
      availabilityFreshness: 'UNAVAILABLE',
      dataUpdatedAt: null,
      sourceLabel: 'OpenStreetMap',
      attribution: 'OSM',
    },
  ],
  municipalTotalInScope: 20,
  municipalHiddenCount: 14,
  communitySpotCountInScope: 5,
};

describe('public Explore anonymous network hard gate', () => {
  beforeEach(() => {
    mockList.mockReset();
    mockList.mockResolvedValue(sampleEnvelope);
    jest.mocked(parkingApi.getNearbySpots).mockClear();
    jest.mocked(parkingApi.getNearbyMunicipalFacilities).mockClear();
    jest.mocked(parkingApi.getMunicipalFacility).mockClear();
    jest.mocked(parkingApi.getSpot).mockClear();
    jest.mocked(geocodingApi.searchPlaces).mockClear();
  });

  it('queryFn calls only publicExploreApi.list with limit<=6 and radius<=5000', async () => {
    const options = publicExploreQueryOptions({
      lat: 38.42,
      lng: 27.14,
      radiusMeters: PUBLIC_EXPLORE_RADIUS_METERS,
      limit: PUBLIC_EXPLORE_LIMIT,
    });
    const data = await options.queryFn!({
      queryKey: options.queryKey,
      signal: new AbortController().signal,
      meta: undefined,
      pageParam: undefined,
      direction: undefined,
    } as never);

    expect(mockList).toHaveBeenCalledTimes(1);
    expect(mockList).toHaveBeenCalledWith(
      expect.objectContaining({
        lat: 38.42,
        lng: 27.14,
        radiusMeters: PUBLIC_EXPLORE_RADIUS_METERS,
        limit: PUBLIC_EXPLORE_LIMIT,
      }),
    );
    expect(data.municipalTotalInScope).toBe(20);
    expect(data.municipalHiddenCount).toBe(14);
    expect(data.communitySpotCountInScope).toBe(5);
    expect(parkingApi.getNearbyMunicipalFacilities).not.toHaveBeenCalled();
    expect(parkingApi.getNearbySpots).not.toHaveBeenCalled();
    expect(parkingApi.getMunicipalFacility).not.toHaveBeenCalled();
    expect(parkingApi.getSpot).not.toHaveBeenCalled();
    expect(geocodingApi.searchPlaces).not.toHaveBeenCalled();
  });

  it('renderable count ignores municipalTotalInScope and invalid coords', () => {
    const renderable = toRenderablePublicFacilities(sampleEnvelope.facilities);
    expect(renderable).toHaveLength(1);
    expect(renderable.length).not.toBe(sampleEnvelope.municipalTotalInScope);
  });

  it('basic preview uses in-memory public DTO only (no private detail)', () => {
    const preview = selectPublicFacilityForPreview(sampleEnvelope.facilities, 'f1');
    expect(preview?.id).toBe('f1');
    expect(preview?.displayName).toBe('A');
    expect(parkingApi.getMunicipalFacility).not.toHaveBeenCalled();
  });

  it('does not fabricate community markers from communitySpotCountInScope', () => {
    expect(sampleEnvelope.communitySpotCountInScope).toBe(5);
    const renderable = toRenderablePublicFacilities(sampleEnvelope.facilities);
    expect(renderable.every((f) => f.id !== 'community')).toBe(true);
  });
});
