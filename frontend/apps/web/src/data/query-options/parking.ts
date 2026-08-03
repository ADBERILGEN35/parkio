import { infiniteQueryOptions, keepPreviousData, queryOptions } from '@tanstack/react-query';
import type {
  MunicipalFacilityNearbyParams,
  NearbySearchParams,
  ParkingSessionHistoryResponse,
} from '@parkio/types';
import type { ParkioSdk } from '@/app/sdk';
import { parkingKeys, type NearbyParkingFilters } from '../keys';

/** Default page size for ParkingSession history (backend allows 1–100). */
export const PARKING_SESSION_HISTORY_PAGE_SIZE = 20;

export function mySpotsQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: parkingKeys.mySpots(),
    queryFn: ({ signal }) => sdk.parkingApi.getMySpots({ signal }),
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

/** Municipal facility nearby — separate query key from community spots. */
export function nearbyMunicipalFacilitiesQueryOptions(
  sdk: ParkioSdk,
  filters: NearbyParkingFilters,
) {
  return queryOptions({
    queryKey: parkingKeys.municipalNearby(filters),
    queryFn: ({ signal }) =>
      sdk.parkingApi.getNearbyMunicipalFacilities(
        filters as MunicipalFacilityNearbyParams,
        signal,
      ),
    placeholderData: keepPreviousData,
    staleTime: 30_000,
  });
}

export function municipalFacilityDetailQueryOptions(sdk: ParkioSdk, facilityId: string) {
  return queryOptions({
    queryKey: parkingKeys.municipalFacility(facilityId),
    queryFn: ({ signal }) => sdk.parkingApi.getMunicipalFacility(facilityId, { signal }),
  });
}

export function spotDetailQueryOptions(sdk: ParkioSdk, spotId: string) {
  return queryOptions({
    queryKey: parkingKeys.spot(spotId),
    queryFn: ({ signal }) => sdk.parkingApi.getSpot(spotId, { signal }),
  });
}

/**
 * Active ParkingSession for the authenticated user.
 * SDK maps HTTP 204 to `null` (normal empty state — not an error).
 * Callers must gate with authentication (`enabled: isAuthenticated`) — this
 * factory alone does not refuse anonymous requests.
 */
export function activeParkingSessionQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: parkingKeys.activeSession(),
    queryFn: ({ signal }) => sdk.parkingApi.getActiveParkingSession(signal),
  });
}

/**
 * Effective confirm / reminder / auto-complete windows from parking-service.
 * Long staleTime — config is env-driven and changes only on redeploy.
 */
export function parkingSessionLifecycleConfigQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: parkingKeys.sessionLifecycleConfig(),
    queryFn: ({ signal }) => sdk.parkingApi.getParkingSessionLifecycleConfig(signal),
    staleTime: 5 * 60_000,
  });
}

/**
 * Cursor-paginated terminal ParkingSession history.
 * Backend order: startedAt DESC, id DESC. ACTIVE rows are not part of the
 * contract; callers still filter defensively before exposing delete actions.
 * Callers must gate with authentication — this factory alone does not refuse
 * anonymous requests.
 */
export function parkingSessionHistoryInfiniteQueryOptions(
  sdk: ParkioSdk,
  size: number = PARKING_SESSION_HISTORY_PAGE_SIZE,
) {
  return infiniteQueryOptions({
    queryKey: parkingKeys.sessionHistory(size),
    queryFn: ({ pageParam, signal }): Promise<ParkingSessionHistoryResponse> =>
      sdk.parkingApi.getParkingSessionHistory(
        pageParam ? { size, cursor: pageParam } : { size },
        signal,
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
  });
}

export function spotMediaAccessUrlQueryOptions(sdk: ParkioSdk, spotId: string) {
  return queryOptions({
    queryKey: parkingKeys.spotMediaAccessUrl(spotId),
    queryFn: ({ signal }) => sdk.parkingApi.getSpotMediaAccessUrl(spotId, { signal }),
    staleTime: 0,
    gcTime: 0,
  });
}
