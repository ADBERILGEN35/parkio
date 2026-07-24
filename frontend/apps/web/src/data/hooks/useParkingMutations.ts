import type { ReportSpotFormValues, VerifySpotFormValues } from '@parkio/validation';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import {
  createClaimSpotMutationOptions,
  createCreateSpotMutationOptions,
  createReportSpotMutationOptions,
  createVerifySpotMutationOptions,
} from '@/data/mutation-options/parking';

export function useVerifySpotMutation(spotId: string) {
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  const options = createVerifySpotMutationOptions(sdk, queryClient, spotId);
  return useMutation({
    mutationFn: (values: VerifySpotFormValues) => options.mutationFn({ result: values.result }),
    onSuccess: options.onSuccess,
  });
}

export function useClaimSpotMutation(spotId: string) {
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  return useMutation(createClaimSpotMutationOptions(sdk, queryClient, spotId));
}

export function useCreateSpotMutation() {
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  return useMutation(createCreateSpotMutationOptions(sdk, queryClient));
}

export function useReportSpotMutation(spotId: string) {
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  const options = createReportSpotMutationOptions(sdk, queryClient, spotId);
  return useMutation({
    mutationFn: (values: ReportSpotFormValues) =>
      options.mutationFn({
        reason: values.reason,
        description: values.description === '' ? null : values.description,
      }),
    onSuccess: options.onSuccess,
  });
}