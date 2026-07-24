import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { SmartReturnSettings, UpdateSmartReturnSettingsRequest } from '@parkio/types';
import { meKeys } from '@/data/keys';
import { mySmartReturnQueryOptions } from '@/data/query-options/me';
import { usersApi } from '@/services/api';

/** Smart Return settings/today state — disabled entirely when feature-flagged off. */
export function useSmartReturn() {
  return useQuery(mySmartReturnQueryOptions());
}

export function useSmartReturnMutations() {
  const queryClient = useQueryClient();
  const sync = (settings: SmartReturnSettings) => {
    queryClient.setQueryData(meKeys.smartReturn(), settings);
  };

  const updateSettings = useMutation({
    mutationFn: (body: UpdateSmartReturnSettingsRequest) => usersApi.updateSmartReturnSettings(body),
    onSuccess: sync,
  });
  const leftByCar = useMutation({
    mutationFn: (expectedReturnAt: string) => usersApi.smartReturnLeftByCar({ expectedReturnAt }),
    onSuccess: sync,
  });
  const notByCar = useMutation({
    mutationFn: () => usersApi.smartReturnNotByCar(),
    onSuccess: sync,
  });
  const updateTime = useMutation({
    mutationFn: (expectedReturnAt: string) => usersApi.updateSmartReturnTime({ expectedReturnAt }),
    onSuccess: sync,
  });
  const cancelToday = useMutation({
    mutationFn: () => usersApi.cancelSmartReturnToday(),
    onSuccess: sync,
  });

  return { updateSettings, leftByCar, notByCar, updateTime, cancelToday };
}

/** Today's date key (device-local) for one-shot morning-prompt gating. */
export function todayKey(now = new Date()): string {
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** ISO timestamp for today at HH:mm (device timezone — Europe/Istanbul beta). */
export function todayAt(hours: number, minutes: number): string {
  const date = new Date();
  date.setHours(hours, minutes, 0, 0);
  return date.toISOString();
}
