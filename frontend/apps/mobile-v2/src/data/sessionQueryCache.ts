import type { QueryClient } from '@tanstack/react-query';
import {
  analyticsKeys,
  gamificationKeys,
  geocodingKeys,
  meKeys,
  moderationKeys,
  notificationsKeys,
  parkingKeys,
  placesKeys,
  recommendationKeys,
  reportsKeys,
} from './keys';

/**
 * User-scoped query roots. Cleared on logout / user switch.
 *
 * Intentionally NOT cleared (PUBLIC_SAFE_ACROSS_SESSIONS):
 * - publicExploreKeys
 * - publicGeocodingKeys
 * - gamification/levels catalog
 *
 * Authenticated nearby / spot / media / municipal / geocoding are SESSION_PRIVATE.
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
  parkingKeys.municipalRoot(),
  placesKeys.all,
  recommendationKeys.all,
  geocodingKeys.all,
];

/**
 * Cancels in-flight user queries then removes cache entries so a late
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
