import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { SmartReturnSettings } from '@parkio/types';
import { usersApi } from '@/services/api';

/**
 * Single cache entry for the user's Smart Return settings — shared with the
 * map's `useSmartReturnHome` so the settings screen and the map deep-link mode
 * never disagree about the saved home.
 */
export const SMART_RETURN_QUERY_KEY = ['users', 'smart-return'] as const;

export function useSmartReturnQuery() {
  return useQuery({
    queryKey: SMART_RETURN_QUERY_KEY,
    queryFn: () => usersApi.getSmartReturn(),
  });
}

/**
 * The four Smart Return mutations. Every endpoint returns the full updated
 * settings, which are written straight into the shared cache entry (no
 * refetch) — same pattern as the web card.
 */
export function useSmartReturnMutations() {
  const queryClient = useQueryClient();
  const apply = (next: SmartReturnSettings) => queryClient.setQueryData(SMART_RETURN_QUERY_KEY, next);

  const saveSettings = useMutation({
    mutationFn: usersApi.updateSmartReturnSettings,
    onSuccess: apply,
  });
  const leftByCar = useMutation({
    mutationFn: usersApi.smartReturnLeftByCar,
    onSuccess: apply,
  });
  const notByCar = useMutation({
    mutationFn: usersApi.smartReturnNotByCar,
    onSuccess: apply,
  });
  const cancelToday = useMutation({
    mutationFn: usersApi.cancelSmartReturnToday,
    onSuccess: apply,
  });

  return { saveSettings, leftByCar, notByCar, cancelToday };
}
