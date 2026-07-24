import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import {
  createCancelSmartReturnTodayMutationOptions,
  createSmartReturnLeftByCarMutationOptions,
  createSmartReturnNotByCarMutationOptions,
  createUpdateSmartReturnSettingsMutationOptions,
} from '@/data/mutation-options/smart-return';

export function useSmartReturnLeftByCarMutation() {
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  return useMutation(createSmartReturnLeftByCarMutationOptions(sdk, queryClient));
}

export function useSmartReturnNotByCarMutation() {
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  return useMutation(createSmartReturnNotByCarMutationOptions(sdk, queryClient));
}

export function useCancelSmartReturnTodayMutation() {
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  return useMutation(createCancelSmartReturnTodayMutationOptions(sdk, queryClient));
}

export function useUpdateSmartReturnSettingsMutation() {
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  return useMutation(createUpdateSmartReturnSettingsMutationOptions(sdk, queryClient));
}