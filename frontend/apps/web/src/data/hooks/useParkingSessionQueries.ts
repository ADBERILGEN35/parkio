import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { useAuthStore } from '@/auth/store';
import {
  PARKING_SESSION_HISTORY_PAGE_SIZE,
  activeParkingSessionQueryOptions,
  parkingSessionHistoryInfiniteQueryOptions,
  parkingSessionLifecycleConfigQueryOptions,
} from '../query-options/parking';

/**
 * Active ParkingSession for the signed-in user. Drives auto-restore of the
 * active card/marker on app open — no user interaction required.
 * Unauthenticated callers never hit the private endpoint.
 */
export function useActiveParkingSessionQuery(options?: { enabled?: boolean }) {
  const sdk = useParkioSdk();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  return useQuery({
    ...activeParkingSessionQueryOptions(sdk),
    enabled: (options?.enabled ?? true) && isAuthenticated,
  });
}

/**
 * Backend confirm/reminder/auto-complete thresholds. Authenticated only;
 * required before evaluating stale UI so clients never hardcode windows.
 */
export function useParkingSessionLifecycleConfigQuery(options?: { enabled?: boolean }) {
  const sdk = useParkioSdk();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  return useQuery({
    ...parkingSessionLifecycleConfigQueryOptions(sdk),
    enabled: (options?.enabled ?? true) && isAuthenticated,
  });
}

/** Cursor-paginated terminal ParkingSession history (Profile → Parking History). */
export function useParkingSessionHistoryQuery(size: number = PARKING_SESSION_HISTORY_PAGE_SIZE) {
  const sdk = useParkioSdk();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  return useInfiniteQuery({
    ...parkingSessionHistoryInfiniteQueryOptions(sdk, size),
    enabled: isAuthenticated,
  });
}
