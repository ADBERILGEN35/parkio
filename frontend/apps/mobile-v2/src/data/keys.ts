import type { NearbySearchParams } from '@parkio/types';

/**
 * Canonical React Query key factories for mobile-v2 server state (WP-07).
 * Mobile-owned - do not import Web key modules. Hierarchy mirrors shared
 * domain semantics so invalidation stays targeted.
 */

export const meKeys = {
  all: ['me'] as const,
  profile: () => [...meKeys.all, 'profile'] as const,
  stats: () => [...meKeys.all, 'stats'] as const,
  vehicle: () => [...meKeys.all, 'vehicle'] as const,
  preferences: () => [...meKeys.all, 'preferences'] as const,
  smartReturn: () => [...meKeys.all, 'smart-return'] as const,
};

export type NearbyParkingFilters = NearbySearchParams;

/** Stable nearby-filter identity: omit undefined optionals. */
export function normalizeNearbyFilters(filters: NearbyParkingFilters) {
  return {
    lat: filters.lat,
    lng: filters.lng,
    ...(filters.radius !== undefined ? { radius: filters.radius } : {}),
    ...(filters.limit !== undefined ? { limit: filters.limit } : {}),
  } as const;
}

export const parkingKeys = {
  all: ['parking'] as const,
  nearby: (filters: NearbyParkingFilters) =>
    [...parkingKeys.all, 'nearby', normalizeNearbyFilters(filters)] as const,
  nearbyRoot: () => [...parkingKeys.all, 'nearby'] as const,
  mySpots: () => [...parkingKeys.all, 'my-spots'] as const,
  spot: (spotId: string) => [...parkingKeys.all, 'spot', spotId] as const,
  spotMediaAccessUrl: (spotId: string) =>
    [...parkingKeys.spot(spotId), 'media-access-url'] as const,
};

export const notificationsKeys = {
  all: ['notifications'] as const,
};

export const reportsKeys = {
  all: ['reports'] as const,
};

export const gamificationKeys = {
  points: () => ['gamification', 'points'] as const,
  level: () => ['gamification', 'level'] as const,
  levels: () => ['gamification', 'levels'] as const,
  progress: () => ['gamification', 'progress'] as const,
  accessPolicy: () => ['gamification', 'access-policy'] as const,
  leaderboard: (limit?: number) =>
    limit === undefined
      ? (['gamification', 'leaderboard'] as const)
      : (['gamification', 'leaderboard', limit] as const),
};

export const geocodingKeys = {
  places: (query: string) => ['geocoding', 'places', query] as const,
};

export const moderationKeys = {
  all: ['moderation'] as const,
  cases: (statusFilter: string) => [...moderationKeys.all, 'cases', statusFilter] as const,
  caseDetail: (caseId: string) => [...moderationKeys.all, 'case', caseId] as const,
  appeals: () => [...moderationKeys.all, 'appeals'] as const,
};

export const analyticsKeys = {
  all: ['analytics'] as const,
  overview: () => [...analyticsKeys.all, 'overview'] as const,
};
