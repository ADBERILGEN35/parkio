import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import { isUsableSpot } from '@parkio/geo';
import type { NearbySearchParams } from '@parkio/types';
import { parkingApi } from '@/services/api';
import { parkingKeys, type NearbyParkingFilters } from '../keys';

export function mySpotsQueryOptions() {
  return queryOptions({
    queryKey: parkingKeys.mySpots(),
    queryFn: ({ signal }) => parkingApi.getMySpots({ signal }),
  });
}

export function nearbySpotsQueryOptions(filters: NearbyParkingFilters) {
  return queryOptions({
    queryKey: parkingKeys.nearby(filters),
    queryFn: async ({ signal }) => {
      const spots = await parkingApi.getNearbySpots(filters as NearbySearchParams, signal);
      return spots.filter(isUsableSpot);
    },
    placeholderData: keepPreviousData,
    staleTime: 10_000,
  });
}

export function spotDetailQueryOptions(spotId: string) {
  return queryOptions({
    queryKey: parkingKeys.spot(spotId),
    queryFn: ({ signal }) => parkingApi.getSpot(spotId, { signal }),
    enabled: spotId.length > 0,
  });
}

export function spotMediaAccessUrlQueryOptions(spotId: string) {
  return queryOptions({
    queryKey: parkingKeys.spotMediaAccessUrl(spotId),
    queryFn: ({ signal }) => parkingApi.getSpotMediaAccessUrl(spotId, { signal }),
    staleTime: 3 * 60_000,
    gcTime: 4 * 60_000,
  });
}
