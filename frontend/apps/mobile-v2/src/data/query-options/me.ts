import { queryOptions } from '@tanstack/react-query';
import { appConfig } from '@/config/env';
import { usersApi } from '@/services/api';
import { meKeys } from '../keys';

export function myProfileQueryOptions() {
  return queryOptions({
    queryKey: meKeys.profile(),
    queryFn: ({ signal }) => usersApi.getMyProfile({ signal }),
  });
}

export function myStatsQueryOptions() {
  return queryOptions({
    queryKey: meKeys.stats(),
    queryFn: ({ signal }) => usersApi.getMyStats({ signal }),
  });
}

export function myVehicleQueryOptions() {
  return queryOptions({
    queryKey: meKeys.vehicle(),
    queryFn: ({ signal }) => usersApi.getMyVehicle({ signal }),
  });
}

export function myPreferencesQueryOptions() {
  return queryOptions({
    queryKey: meKeys.preferences(),
    queryFn: ({ signal }) => usersApi.getMyPreferences({ signal }),
  });
}

export function mySmartReturnQueryOptions() {
  return queryOptions({
    queryKey: meKeys.smartReturn(),
    queryFn: ({ signal }) => usersApi.getSmartReturn({ signal }),
    enabled: appConfig.features.smartReturn,
    staleTime: 60_000,
  });
}
