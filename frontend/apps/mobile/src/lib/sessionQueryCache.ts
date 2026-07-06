import type { QueryClient } from '@tanstack/react-query';
import { SMART_RETURN_QUERY_KEY } from '@/features/smart-return/hooks/useSmartReturnSettings';

/**
 * Query key roots scoped to the signed-in user. Must not survive logout or account switch.
 */
export const USER_SESSION_QUERY_ROOTS: readonly (readonly string[])[] = [
  ['me'],
  ['progress'],
  ['points'],
  ['level'],
  ['access-policy'],
  ['notifications'],
  ['leaderboard'],
  ['reports'],
  ['moderation'],
  ['analytics'],
  ['parking', 'my-spots'],
  SMART_RETURN_QUERY_KEY,
];

export function clearUserSessionQueries(queryClient: QueryClient): void {
  for (const queryKey of USER_SESSION_QUERY_ROOTS) {
    queryClient.removeQueries({ queryKey });
  }
}