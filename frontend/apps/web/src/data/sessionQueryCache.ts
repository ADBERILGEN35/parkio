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
 * Query key roots scoped to the signed-in user. Cleared on logout,
 * refresh-failure teardown, or account switch.
 *
 * Intentionally NOT cleared (PUBLIC_SAFE_ACROSS_SESSIONS):
 * - inline `public-explore` keys on PublicExplorePage
 * - `gamification/levels` catalog
 * - `public-profile/*`
 *
 * Authenticated community nearby / spot detail / signed media / municipal
 * inventory are SESSION_PRIVATE (separate from anonymous public-explore).
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
  parkingKeys.nearbyRoot(),
  parkingKeys.spotRoot(),
  parkingKeys.municipalNearbyRoot(),
  // Facility detail shares municipal-facility segment under parking.
  [...parkingKeys.all, 'municipal-facility'] as const,
  placesKeys.all,
  recommendationKeys.all,
  adminKeys.all,
];

/**
 * Cancels in-flight user queries then removes their cache entries so a late
 * response cannot repopulate sensitive data after logout or user switch.
 */
export async function clearUserSessionQueries(queryClient: QueryClient): Promise<void> {
  await Promise.all(
    USER_SESSION_QUERY_ROOTS.map((queryKey) => queryClient.cancelQueries({ queryKey })),
  );
  for (const queryKey of USER_SESSION_QUERY_ROOTS) {
    queryClient.removeQueries({ queryKey });
  }
}
