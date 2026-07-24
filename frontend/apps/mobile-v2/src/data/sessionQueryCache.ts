import type { QueryClient } from '@tanstack/react-query';
import {
  analyticsKeys,
  gamificationKeys,
  meKeys,
  moderationKeys,
  notificationsKeys,
  parkingKeys,
  reportsKeys,
} from './keys';

/**
 * User-scoped query roots. Cleared on logout / user switch.
 * Public nearby discovery cache is intentionally preserved.
 */
export const USER_SESSION_QUERY_ROOTS: readonly (readonly unknown[])[] = [
  meKeys.all,
  gamificationKeys.progress(),
  gamificationKeys.points(),
  gamificationKeys.level(),
  gamificationKeys.accessPolicy(),
  gamificationKeys.leaderboard(),
  notificationsKeys.all,
  reportsKeys.all,
  moderationKeys.all,
  analyticsKeys.all,
  parkingKeys.mySpots(),
  parkingKeys.sessionsRoot(),
];

/**
 * Cancels in-flight user queries then removes cache entries so a late
 * response cannot repopulate sensitive data after logout or user switch.
 */
export function clearUserSessionQueries(queryClient: QueryClient): void {
  for (const queryKey of USER_SESSION_QUERY_ROOTS) {
    void queryClient.cancelQueries({ queryKey });
    queryClient.removeQueries({ queryKey });
  }
}
