import { useQuery } from '@tanstack/react-query';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { useAuthStore } from '@/auth/store';
import {
  myPreferencesLocaleBootstrapQueryOptions,
  myPreferencesQueryOptions,
  myProfileQueryOptions,
  mySmartReturnQueryOptions,
  myStatsQueryOptions,
  myVehicleQueryOptions,
} from '../query-options/me';

export function useMyProfileQuery() {
  const sdk = useParkioSdk();
  return useQuery(myProfileQueryOptions(sdk));
}

export function useMyStatsQuery() {
  const sdk = useParkioSdk();
  return useQuery(myStatsQueryOptions(sdk));
}

export function useMyVehicleQuery(options?: { enabled?: boolean; staleTime?: number }) {
  const sdk = useParkioSdk();
  return useQuery({
    ...myVehicleQueryOptions(sdk),
    enabled: options?.enabled ?? true,
    ...(options?.staleTime !== undefined ? { staleTime: options.staleTime } : {}),
  });
}

export function useMyPreferencesQuery() {
  const sdk = useParkioSdk();
  return useQuery(myPreferencesQueryOptions(sdk));
}

export function useMyPreferencesLocaleBootstrapQuery(options?: { enabled?: boolean }) {
  const sdk = useParkioSdk();
  return useQuery({
    ...myPreferencesLocaleBootstrapQueryOptions(sdk),
    enabled: options?.enabled ?? true,
  });
}

export function useMySmartReturnQuery(options?: { enabled?: boolean; staleTime?: number }) {
  const sdk = useParkioSdk();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  return useQuery({
    ...mySmartReturnQueryOptions(sdk),
    enabled: (options?.enabled ?? true) && isAuthenticated,
    ...(options?.staleTime !== undefined ? { staleTime: options.staleTime } : {}),
  });
}
