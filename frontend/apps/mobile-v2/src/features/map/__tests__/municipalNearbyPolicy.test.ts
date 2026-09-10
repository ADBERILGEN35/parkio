import { DEFAULT_NEARBY_RADIUS_M } from '@parkio/geo';

const mockMunicipalDiscovery = jest.fn(() => true);

jest.mock('@/config/env', () => ({
  appConfig: {
    appEnv: 'development',
    isProductionLike: false,
    apiBaseUrl: 'http://localhost:8080/api/v1',
    get features() {
      return {
        smartReturn: true,
        municipalDiscovery: mockMunicipalDiscovery(),
      };
    },
  },
}));

jest.mock('@/services/api', () => ({
  parkingApi: {
    getNearbyMunicipalFacilities: jest.fn(async () => []),
    getNearbySpots: jest.fn(async () => []),
  },
}));

jest.mock('@/data/query-options/gamification', () => ({
  accessPolicyQueryOptions: () => ({ queryKey: ['gamification', 'access-policy'], queryFn: async () => null }),
}));

jest.mock('@/data/query-options/geocoding', () => ({
  placeSearchQueryOptions: () => ({ queryKey: ['geocoding'], queryFn: async () => [] }),
}));

import { nearbyMunicipalFacilitiesQueryOptions } from '@/data/query-options/parking';
import * as api from '@/services/api';

describe('municipal nearby query integration policy', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockMunicipalDiscovery.mockReturnValue(true);
  });

  it('uses canonical radiusMeters from the map policy', async () => {
    const signal = new AbortController().signal;
    const options = nearbyMunicipalFacilitiesQueryOptions({
      lat: 38.42,
      lng: 27.14,
      radiusMeters: DEFAULT_NEARBY_RADIUS_M,
      limit: 50,
    });
    expect(options.enabled).toBe(true);
    await options.queryFn!({ signal } as never);
    expect(api.parkingApi.getNearbyMunicipalFacilities).toHaveBeenCalledWith(
      expect.objectContaining({
        lat: 38.42,
        lng: 27.14,
        radiusMeters: DEFAULT_NEARBY_RADIUS_M,
        limit: 50,
      }),
      signal,
    );
    expect(api.parkingApi.getNearbySpots).not.toHaveBeenCalled();
  });

  it('disables municipal nearby when the feature flag is off', () => {
    mockMunicipalDiscovery.mockReturnValue(false);
    const options = nearbyMunicipalFacilitiesQueryOptions({
      lat: 38.42,
      lng: 27.14,
      radiusMeters: 1500,
    });
    expect(options.enabled).toBe(false);
  });
});
