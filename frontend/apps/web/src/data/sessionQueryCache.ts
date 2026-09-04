import type { QueryClient } from '@tanstack/react-query';
import {
  adminKeys,
  analyticsKeys,
  gamificationKeys,
  meKeys,
  moderationKeys,
  notificationsKeys,
  parkingKeys,
  placesKeys,
  recommendationKeys,
  reportsKeys,
} from './keys';

/**
 * Query key roots scoped to the signed-in user. Must not survive logout,
 * refresh-failure teardown, or account switch. Public discovery cache
 * (`parking.nearby`) is intentionally preserved.
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
  placesKeys.all,
  recommendationKeys.all,
  adminKeys.all,
];

/**
 * Cancels in-flight user queries then removes their cache entries so a late
 * response cannot repopulate sensitive data after logout or user switch.
 */
export function clearUserSessionQueries(queryClient: QueryClient): void {
  for (const queryKey of USER_SESSION_QUERY_ROOTS) {
    void queryClient.cancelQueries({ queryKey });
    queryClient.removeQueries({ queryKey });
  }
}
