import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import type { NearbySearchParams } from '@parkio/types';
import type { ParkioSdk } from '@/app/sdk';
import { parkingKeys, type NearbyParkingFilters } from '../keys';

export function mySpotsQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: parkingKeys.mySpots(),
    queryFn: () => sdk.parkingApi.getMySpots(),
  });
}

export function nearbySpotsQueryOptions(sdk: ParkioSdk, filters: NearbyParkingFilters) {
  return queryOptions({
    queryKey: parkingKeys.nearby(filters),
    queryFn: ({ signal }) =>
      sdk.parkingApi.getNearbySpots(filters as NearbySearchParams, signal),
    placeholderData: keepPreviousData,
    staleTime: 30_000,
  });
}

export function spotDetailQueryOptions(sdk: ParkioSdk, spotId: string) {
  return queryOptions({
    queryKey: parkingKeys.spot(spotId),
    queryFn: () => sdk.parkingApi.getSpot(spotId),
  });
}

export function spotMediaAccessUrlQueryOptions(sdk: ParkioSdk, spotId: string) {
  return queryOptions({
    queryKey: parkingKeys.spotMediaAccessUrl(spotId),
    queryFn: () => sdk.parkingApi.getSpotMediaAccessUrl(spotId),
    staleTime: 0,
    gcTime: 0,
  });
}
