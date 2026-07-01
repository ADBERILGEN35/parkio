import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createIdempotencyKey, isParkioApiError } from '@parkio/api-client';
import type { Spot } from '@parkio/types';
import { parkingApi } from '@/services/api';
import { toUserMessage } from '@/utils/errors';
import { buildCreateSpotRequest } from '../lib/createSpotRequest';
import { useSpotCreationDraftStore } from '../state/spotCreationDraftStore';

function isTransientFailure(error: unknown): boolean {
  if (isParkioApiError(error)) {
    return error.status === 408 || error.status === 429 || error.status >= 500;
  }
  if (typeof error !== 'object' || error === null) return false;
  const message = (error as { message?: string }).message ?? '';
  const code = (error as { code?: string }).code;
  return code === 'ERR_NETWORK' || code === 'ECONNABORTED' || /network|timeout/i.test(message);
}

export function useCreateSpotSubmit() {
  const queryClient = useQueryClient();
  const draft = useSpotCreationDraftStore((state) => state.draft);
  const patchDraft = useSpotCreationDraftStore((state) => state.patchDraft);
  const clearDraft = useSpotCreationDraftStore((state) => state.clearDraft);

  const mutation = useMutation({
    mutationFn: async (): Promise<Spot> => {
      const result = buildCreateSpotRequest(draft);
      if (!result.request) {
        throw new Error(result.error ?? 'Please check the spot details.');
      }
      const key = draft?.submitIdempotencyKey ?? createIdempotencyKey();
      if (!draft?.submitIdempotencyKey) {
        patchDraft({ submitIdempotencyKey: key });
      }
      return parkingApi.createParkingSpot(result.request, key);
    },
    retry: (failureCount, error) => failureCount < 2 && isTransientFailure(error),
    onSuccess: (spot) => {
      clearDraft();
      queryClient.setQueryData(['parking', 'spot', spot.id], spot);
      void queryClient.invalidateQueries({ queryKey: ['parking', 'nearby'] });
      void queryClient.invalidateQueries({ queryKey: ['parking', 'my-spots'] });
    },
  });

  return {
    ...mutation,
    errorMessage: mutation.error ? toUserMessage(mutation.error) : null,
  };
}
