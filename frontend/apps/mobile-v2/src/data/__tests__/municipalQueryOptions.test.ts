import type { MunicipalFacility } from '@parkio/types';
import { parkingKeys } from '../keys';
import * as api from '@/services/api';

const mockMunicipalDiscovery = jest.fn(() => false);

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
    getNearbySpots: jest.fn(async () => []),
    getNearbyMunicipalFacilities: jest.fn(async () => []),
    getMunicipalFacility: jest.fn(async () => ({ id: 'f1' })),
    getSpot: jest.fn(async () => ({ id: 's1' })),
  },
}));

import {
  municipalFacilityDetailQueryOptions,
  nearbyMunicipalFacilitiesQueryOptions,
} from '../query-options/parking';

const facility: MunicipalFacility = {
  id: '70db58f2-4cca-4010-9315-fa46b30fba1e',
  displayName: 'Alsancak İZUM',
  operatorName: 'İZUM',
  facilityType: 'OFF_STREET',
  addressText: 'Alsancak',
  latitude: 38.43,
  longitude: 27.14,
  capacityTotal: 59,
  availableSpaces: 1,
  occupiedSpaces: 58,
  freshness: 'LIVE',
  attribution: null,
  sourceLabel: 'IZUM',
  lastUpdatedAt: '2026-08-05T12:00:00Z',
  contributingSourceKeys: ['izmir-izum-otoparklar'],
  selectedFieldProvenanceSummary: null,
  registryConfidenceOrReviewStatus: null,
  availabilitySource: 'izmir-izum-otoparklar',
  availabilityFreshness: 'LIVE',
  availabilityObservationTimestamp: '2026-08-05T12:00:00Z',
};

describe('nearbyMunicipalFacilitiesQueryOptions', () => {
  const signal = new AbortController().signal;
  const filters = { lat: 38.42, lng: 27.14, radiusMeters: 1500, limit: 40 };

  beforeEach(() => {
    jest.clearAllMocks();
    mockMunicipalDiscovery.mockReturnValue(false);
  });

  it('is disabled and does not schedule a municipal request when the flag is off', () => {
    mockMunicipalDiscovery.mockReturnValue(false);
    const options = nearbyMunicipalFacilitiesQueryOptions(filters);
    expect(options.enabled).toBe(false);
    expect(api.parkingApi.getNearbyMunicipalFacilities).not.toHaveBeenCalled();
    expect(api.parkingApi.getNearbySpots).not.toHaveBeenCalled();
  });

  it('is enabled for flag on + valid coordinates', () => {
    mockMunicipalDiscovery.mockReturnValue(true);
    expect(nearbyMunicipalFacilitiesQueryOptions(filters).enabled).toBe(true);
  });

  it('is disabled when coordinates are missing (NaN / non-finite)', () => {
    mockMunicipalDiscovery.mockReturnValue(true);
    expect(
      nearbyMunicipalFacilitiesQueryOptions({
        lat: Number.NaN,
        lng: 27.14,
        radiusMeters: 1500,
      }).enabled,
    ).toBe(false);
  });

  it('is disabled for invalid radiusMeters', () => {
    mockMunicipalDiscovery.mockReturnValue(true);
    expect(
      nearbyMunicipalFacilitiesQueryOptions({
        lat: 38.42,
        lng: 27.14,
        radiusMeters: 0,
      }).enabled,
    ).toBe(false);
  });

  it('uses the canonical municipal nearby key', () => {
    mockMunicipalDiscovery.mockReturnValue(true);
    expect(nearbyMunicipalFacilitiesQueryOptions(filters).queryKey).toEqual(
      parkingKeys.municipalNearby(filters),
    );
  });

  it('passes radiusMeters exactly and preserves MunicipalFacility results', async () => {
    mockMunicipalDiscovery.mockReturnValue(true);
    jest.mocked(api.parkingApi.getNearbyMunicipalFacilities).mockResolvedValueOnce([facility]);

    const options = nearbyMunicipalFacilitiesQueryOptions(filters);
    const result = await options.queryFn!({ signal } as never);

    expect(api.parkingApi.getNearbyMunicipalFacilities).toHaveBeenCalledWith(
      {
        lat: 38.42,
        lng: 27.14,
        radiusMeters: 1500,
        limit: 40,
      },
      signal,
    );
    expect(api.parkingApi.getNearbySpots).not.toHaveBeenCalled();
    expect(result).toEqual([facility]);
    expect(result[0]?.occupiedSpaces).toBe(58);
  });
});

describe('municipalFacilityDetailQueryOptions', () => {
  const signal = new AbortController().signal;
  const facilityId = '70db58f2-4cca-4010-9315-fa46b30fba1e';

  beforeEach(() => {
    jest.clearAllMocks();
    mockMunicipalDiscovery.mockReturnValue(false);
  });

  it('is disabled when the flag is off', () => {
    mockMunicipalDiscovery.mockReturnValue(false);
    expect(municipalFacilityDetailQueryOptions(facilityId).enabled).toBe(false);
  });

  it('is disabled for blank facility id', () => {
    mockMunicipalDiscovery.mockReturnValue(true);
    expect(municipalFacilityDetailQueryOptions('   ').enabled).toBe(false);
  });

  it('is enabled for flag on + valid id', () => {
    mockMunicipalDiscovery.mockReturnValue(true);
    expect(municipalFacilityDetailQueryOptions(facilityId).enabled).toBe(true);
  });

  it('uses a unique detail key under municipalRoot', () => {
    expect(municipalFacilityDetailQueryOptions(facilityId).queryKey).toEqual(
      parkingKeys.municipalFacility(facilityId),
    );
    expect(municipalFacilityDetailQueryOptions(facilityId).queryKey).not.toEqual(
      parkingKeys.spot(facilityId),
    );
  });

  it('calls getMunicipalFacility and preserves the full DTO', async () => {
    mockMunicipalDiscovery.mockReturnValue(true);
    jest.mocked(api.parkingApi.getMunicipalFacility).mockResolvedValueOnce(facility);

    const options = municipalFacilityDetailQueryOptions(facilityId);
    const result = await options.queryFn!({ signal } as never);

    expect(api.parkingApi.getMunicipalFacility).toHaveBeenCalledWith(facilityId, { signal });
    expect(result).toEqual(facility);
    expect(result.occupiedSpaces).toBe(58);
    expect(result.availableSpaces).toBe(1);
    expect(result.capacityTotal).toBe(59);
  });
});
