import { infiniteQueryOptions, keepPreviousData, queryOptions } from '@tanstack/react-query';
import { isUsableSpot } from '@parkio/geo';
import type { NearbySearchParams, ParkingSessionHistoryResponse } from '@parkio/types';
import { parkingApi } from '@/services/api';
import { parkingKeys, type NearbyParkingFilters } from '../keys';

/** Default page size for ParkingSession history (backend allows 1–100). */
export const PARKING_SESSION_HISTORY_PAGE_SIZE = 20;

export function mySpotsQueryOptions() {
  return queryOptions({
    queryKey: parkingKeys.mySpots(),
    queryFn: ({ signal }) => parkingApi.getMySpots({ signal }),
  });
}

/**
 * Active ParkingSession for the authenticated user.
 * SDK maps HTTP 204 to `null` (normal empty — not an error).
 */
export function activeParkingSessionQueryOptions() {
  return queryOptions({
    queryKey: parkingKeys.activeSession(),
    queryFn: ({ signal }) => parkingApi.getActiveParkingSession(signal),
  });
}

/**
 * Cursor-paginated terminal ParkingSession history (S1-P0-11).
 * Backend order: startedAt DESC, id DESC. ACTIVE rows are not part of the contract;
 * callers still filter defensively before exposing delete actions.
 */
export function parkingSessionHistoryInfiniteQueryOptions(
  size: number = PARKING_SESSION_HISTORY_PAGE_SIZE,
) {
  return infiniteQueryOptions({
    queryKey: parkingKeys.sessionHistory(size),
    queryFn: ({ pageParam, signal }): Promise<ParkingSessionHistoryResponse> =>
      parkingApi.getParkingSessionHistory(
        pageParam ? { size, cursor: pageParam } : { size },
        signal,
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
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
