import type { NearbySearchParams } from '@parkio/types';

/**
 * Canonical React Query key factories for Web server state (WP-04).
 * Domain hierarchy is stable and serializable; filters are normalized so
 * equivalent requests share one cache identity.
 */

export const meKeys = {
  all: ['me'] as const,
  profile: () => [...meKeys.all, 'profile'] as const,
  stats: () => [...meKeys.all, 'stats'] as const,
  vehicle: () => [...meKeys.all, 'vehicle'] as const,
  preferences: () => [...meKeys.all, 'preferences'] as const,
  /** Locale bootstrap reads preferences without sharing the profile form cache entry. */
  preferencesLocaleBootstrap: () => [...meKeys.preferences(), 'locale-bootstrap'] as const,
  smartReturn: () => [...meKeys.all, 'smart-return'] as const,
};

export type NearbyParkingFilters = NearbySearchParams;

/** Stable nearby-filter identity: omit undefined optionals; keep numeric order fixed. */
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
  /** User-scoped ParkingSession hierarchy (precise coordinates — cleared on logout). */
  sessionsRoot: () => [...parkingKeys.all, 'sessions'] as const,
  activeSession: () => [...parkingKeys.sessionsRoot(), 'active'] as const,
  /**
   * Cursor-paginated terminal history. Scoped under sessionsRoot so logout / user
   * switch clears it together with active-session data.
   */
  sessionHistory: (size: number) =>
    [...parkingKeys.sessionsRoot(), 'history', { size }] as const,
  sessionHistoryRoot: () => [...parkingKeys.sessionsRoot(), 'history'] as const,
};

export const notificationsKeys = {
  all: ['notifications'] as const,
};

export const reportsKeys = {
  all: ['reports'] as const,
};

export const gamificationKeys = {
  points: () => ['points'] as const,
  level: () => ['level'] as const,
  levels: () => ['levels'] as const,
  progress: () => ['progress'] as const,
  accessPolicy: () => ['access-policy'] as const,
  leaderboard: (limit?: number) =>
    limit === undefined
      ? (['leaderboard'] as const)
      : (['leaderboard', limit] as const),
};

export const publicProfileKeys = {
  detail: (userId: string) => ['public-profile', userId] as const,
};

export const moderationKeys = {
  all: ['moderation'] as const,
  cases: (statusFilter: string) => [...moderationKeys.all, 'cases', statusFilter] as const,
  casesRoot: () => [...moderationKeys.all, 'cases'] as const,
  caseDetail: (caseId: string) => [...moderationKeys.all, 'case', caseId] as const,
  appeals: () => [...moderationKeys.all, 'appeals'] as const,
};

export const analyticsKeys = {
  all: ['analytics'] as const,
  overview: () => [...analyticsKeys.all, 'overview'] as const,
  daily: () => [...analyticsKeys.all, 'daily'] as const,
  parking: () => [...analyticsKeys.all, 'parking'] as const,
  metrics: () => [...analyticsKeys.all, 'metrics'] as const,
  user: (userId: string) => [...analyticsKeys.all, 'user', userId] as const,
};

export const adminKeys = {
  all: ['admin'] as const,
  dashboard: () => [...adminKeys.all, 'dashboard'] as const,
  security: () => [...adminKeys.all, 'security'] as const,
  users: (q: string, status: string, page: number) =>
    [...adminKeys.all, 'users', q, status, page] as const,
  userDetail: (id: string) => [...adminKeys.all, 'users', id] as const,
  audit: (page: number, result: string) => [...adminKeys.all, 'audit', page, result] as const,
};
