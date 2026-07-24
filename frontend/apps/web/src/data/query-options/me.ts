import { queryOptions } from '@tanstack/react-query';
import type { ParkioSdk } from '@/app/sdk';
import { meKeys } from '../keys';

export function myProfileQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: meKeys.profile(),
    queryFn: () => sdk.usersApi.getMyProfile(),
  });
}

export function myStatsQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: meKeys.stats(),
    queryFn: () => sdk.usersApi.getMyStats(),
  });
}

export function myVehicleQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: meKeys.vehicle(),
    queryFn: () => sdk.usersApi.getMyVehicle(),
  });
}

export function myPreferencesQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: meKeys.preferences(),
    queryFn: () => sdk.usersApi.getMyPreferences(),
  });
}

export function myPreferencesLocaleBootstrapQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: meKeys.preferencesLocaleBootstrap(),
    queryFn: () => sdk.usersApi.getMyPreferences(),
    staleTime: 60_000,
  });
}

export function mySmartReturnQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: meKeys.smartReturn(),
    queryFn: () => sdk.usersApi.getSmartReturn(),
  });
}
