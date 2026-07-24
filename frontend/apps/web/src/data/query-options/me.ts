import { queryOptions } from '@tanstack/react-query';
import type { ParkioSdk } from '@/app/sdk';
import { meKeys } from '../keys';

export function myProfileQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: meKeys.profile(),
    queryFn: ({ signal }) => sdk.usersApi.getMyProfile({ signal }),
  });
}

export function myStatsQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: meKeys.stats(),
    queryFn: ({ signal }) => sdk.usersApi.getMyStats({ signal }),
  });
}

export function myVehicleQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: meKeys.vehicle(),
    queryFn: ({ signal }) => sdk.usersApi.getMyVehicle({ signal }),
  });
}

export function myPreferencesQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: meKeys.preferences(),
    queryFn: ({ signal }) => sdk.usersApi.getMyPreferences({ signal }),
  });
}

export function myPreferencesLocaleBootstrapQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: meKeys.preferencesLocaleBootstrap(),
    queryFn: ({ signal }) => sdk.usersApi.getMyPreferences({ signal }),
    staleTime: 60_000,
  });
}

export function mySmartReturnQueryOptions(sdk: ParkioSdk) {
  return queryOptions({
    queryKey: meKeys.smartReturn(),
    queryFn: ({ signal }) => sdk.usersApi.getSmartReturn({ signal }),
  });
}
