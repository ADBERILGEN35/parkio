import type { SmartReturnSettings } from '@parkio/types';
import type { QueryClient } from '@tanstack/react-query';
import type { ParkioSdk } from '@/app/sdk';
import { meKeys } from '@/data/keys';

/** Canonical Smart Return cache write — response is the authoritative settings entity. */
export function applySmartReturnSettings(
  queryClient: QueryClient,
  next: SmartReturnSettings,
): void {
  queryClient.setQueryData(meKeys.smartReturn(), next);
}

export function createSmartReturnLeftByCarMutationOptions(
  sdk: ParkioSdk,
  queryClient: QueryClient,
) {
  return {
    mutationFn: sdk.usersApi.smartReturnLeftByCar,
    onSuccess: (next: SmartReturnSettings) => {
      applySmartReturnSettings(queryClient, next);
    },
  };
}

export function createSmartReturnNotByCarMutationOptions(
  sdk: ParkioSdk,
  queryClient: QueryClient,
) {
  return {
    mutationFn: sdk.usersApi.smartReturnNotByCar,
    onSuccess: (next: SmartReturnSettings) => {
      applySmartReturnSettings(queryClient, next);
    },
  };
}

export function createCancelSmartReturnTodayMutationOptions(
  sdk: ParkioSdk,
  queryClient: QueryClient,
) {
  return {
    mutationFn: sdk.usersApi.cancelSmartReturnToday,
    onSuccess: (next: SmartReturnSettings) => {
      applySmartReturnSettings(queryClient, next);
    },
  };
}

export function createUpdateSmartReturnSettingsMutationOptions(
  sdk: ParkioSdk,
  queryClient: QueryClient,
) {
  return {
    mutationFn: sdk.usersApi.updateSmartReturnSettings,
    onSuccess: (next: SmartReturnSettings) => {
      applySmartReturnSettings(queryClient, next);
    },
  };
}