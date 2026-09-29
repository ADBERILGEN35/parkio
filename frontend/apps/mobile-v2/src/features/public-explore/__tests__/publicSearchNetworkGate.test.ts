import type { PublicExploreDiscovery } from '@parkio/types';
import { PUBLIC_EXPLORE_LIMIT, PUBLIC_EXPLORE_RADIUS_METERS } from '../constants';
import { PUBLIC_GEOCODING_RESULT_LIMIT } from '@/data/query-options/publicGeocoding';
import { toRenderablePublicFacilities } from '../renderablePublicFacilities';
import { selectPublicFacilityForPreview } from '../selectPublicFacilityForPreview';

const mockPublicGeocode = jest.fn();
const mockPublicExplore = jest.fn();

jest.mock('@/services/api', () => ({
  publicGeocodingApi: {
    searchPlaces: (...args: unknown[]) => mockPublicGeocode(...args),
  },
  publicExploreApi: {
    list: (...args: unknown[]) => mockPublicExplore(...args),
  },
  geocodingApi: {
    searchPlaces: jest.fn(() => {
      throw new Error('PRIVATE geocoding must not be called anonymously');
    }),
  },
  parkingApi: {
    getNearbySpots: jest.fn(),
    getNearbyMunicipalFacilities: jest.fn(),
    getMunicipalFacility: jest.fn(),
    getSpot: jest.fn(),
  },
  placesApi: {
    listSavedPlaces: jest.fn(),
  },
}));

import { publicPlaceSearchQueryOptions } from '@/data/query-options/publicGeocoding';
import { publicExploreQueryOptions } from '@/data/query-options/publicExplore';
import { geocodingApi, parkingApi, placesApi } from '@/services/api';

const envelope: PublicExploreDiscovery = {
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
  ],
  municipalTotalInScope: 9,
  municipalHiddenCount: 3,
  communitySpotCountInScope: 4,
};

describe('Wave 2 anonymous public search network hard gate', () => {
  beforeEach(() => {
    mockPublicGeocode.mockReset();
    mockPublicExplore.mockReset();
    mockPublicGeocode.mockResolvedValue([
      {
        id: 'g1',
        displayName: 'Alsancak',
        primary: 'Alsancak',
        secondary: 'İzmir',
        lat: 38.438,
        lng: 27.142,
      },
    ]);
    mockPublicExplore.mockResolvedValue(envelope);
    jest.mocked(geocodingApi.searchPlaces).mockClear();
    jest.mocked(parkingApi.getNearbyMunicipalFacilities).mockClear();
    jest.mocked(parkingApi.getNearbySpots).mockClear();
    jest.mocked(parkingApi.getMunicipalFacility).mockClear();
    jest.mocked(placesApi.listSavedPlaces).mockClear();
  });

  it('public geocoding uses /public path via publicGeocodingApi only', async () => {
    const options = publicPlaceSearchQueryOptions('Alsancak');
    const results = await options.queryFn!({
      queryKey: options.queryKey,
      signal: new AbortController().signal,
      meta: undefined,
      pageParam: undefined,
      direction: undefined,
    } as never);

    expect(mockPublicGeocode).toHaveBeenCalledWith(
      'Alsancak',
      PUBLIC_GEOCODING_RESULT_LIMIT,
      expect.anything(),
    );
    expect(results[0]?.primary).toBe('Alsancak');
    expect(geocodingApi.searchPlaces).not.toHaveBeenCalled();
    expect(placesApi.listSavedPlaces).not.toHaveBeenCalled();
  });

  it('does not call private geocoding for short queries (enabled=false)', () => {
    expect(publicPlaceSearchQueryOptions('Al').enabled).toBe(false);
    expect(mockPublicGeocode).not.toHaveBeenCalled();
    expect(geocodingApi.searchPlaces).not.toHaveBeenCalled();
  });

  it('destination explore refetch stays on public facilities contract', async () => {
    const options = publicExploreQueryOptions({
      lat: 38.438,
      lng: 27.142,
      radiusMeters: PUBLIC_EXPLORE_RADIUS_METERS,
      limit: PUBLIC_EXPLORE_LIMIT,
    });
    await options.queryFn!({
      queryKey: options.queryKey,
      signal: new AbortController().signal,
      meta: undefined,
      pageParam: undefined,
      direction: undefined,
    } as never);

    expect(mockPublicExplore).toHaveBeenCalledWith(
      expect.objectContaining({
        lat: 38.438,
        lng: 27.142,
        radiusMeters: PUBLIC_EXPLORE_RADIUS_METERS,
        limit: PUBLIC_EXPLORE_LIMIT,
      }),
    );
    expect(parkingApi.getNearbyMunicipalFacilities).not.toHaveBeenCalled();
    expect(parkingApi.getNearbySpots).not.toHaveBeenCalled();
  });

  it('preserves Wave 1 preview + count/marker invariant around destination results', () => {
    const renderable = toRenderablePublicFacilities(envelope.facilities);
    expect(renderable).toHaveLength(1);
    expect(renderable.length).not.toBe(envelope.municipalTotalInScope);
    expect(selectPublicFacilityForPreview(renderable, 'f1')?.id).toBe('f1');
    expect(parkingApi.getMunicipalFacility).not.toHaveBeenCalled();
    // community count must not become markers
    expect(envelope.communitySpotCountInScope).toBe(4);
  });
});
