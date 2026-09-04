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

/**
 * Municipal nearby filters — separate inventory from community spots.
 * Canonical radius is {@code radiusMeters} (never the legacy spot `radius` key).
 */
export interface NearbyMunicipalFilters {
  lat: number;
  lng: number;
  radiusMeters: number;
  limit?: number;
}

/** Stable nearby-filter identity: omit undefined optionals. */
export function normalizeNearbyFilters(filters: NearbyParkingFilters) {
  return {
    lat: filters.lat,
    lng: filters.lng,
    ...(filters.radius !== undefined ? { radius: filters.radius } : {}),
    ...(filters.limit !== undefined ? { limit: filters.limit } : {}),
  } as const;
}

/** Stable municipal nearby identity — includes every result-affecting param. */
export function normalizeMunicipalNearbyFilters(filters: NearbyMunicipalFilters) {
  return {
    lat: filters.lat,
    lng: filters.lng,
    radiusMeters: filters.radiusMeters,
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
  /**
   * Municipal facility hierarchy (MOBILE-MUNI-V2-01).
   * Sibling of spot nearby — never fuse inventories or share key segments beyond `parking`.
   */
  municipalRoot: () => [...parkingKeys.all, 'municipal'] as const,
  municipalNearby: (filters: NearbyMunicipalFilters) =>
    [
      ...parkingKeys.municipalRoot(),
      'nearby',
      normalizeMunicipalNearbyFilters(filters),
    ] as const,
  municipalNearbyRoot: () => [...parkingKeys.municipalRoot(), 'nearby'] as const,
  municipalFacility: (facilityId: string) =>
    [...parkingKeys.municipalRoot(), 'facility', facilityId] as const,
  /** User-scoped ParkingSession hierarchy (precise coordinates — cleared on logout). */
  sessionsRoot: () => [...parkingKeys.all, 'sessions'] as const,
  activeSession: () => [...parkingKeys.sessionsRoot(), 'active'] as const,
  sessionLifecycleConfig: () => [...parkingKeys.sessionsRoot(), 'lifecycle-config'] as const,
  /**
   * Cursor-paginated terminal history. Scoped under sessionsRoot so logout/user
   * switch clears it with active session data (S1-P0-11).
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

/** User-scoped places hierarchy — clear on logout / user switch with session caches. */
export const placesKeys = {
  all: ['places'] as const,
  savedRoot: () => [...placesKeys.all, 'saved'] as const,
  saved: () => [...placesKeys.savedRoot(), 'list'] as const,
  favouritesRoot: () => [...placesKeys.all, 'favourites'] as const,
  favouriteDestinations: () => [...placesKeys.favouritesRoot(), 'destinations'] as const,
  favouriteParking: () => [...placesKeys.favouritesRoot(), 'parking'] as const,
  recentsRoot: () => [...placesKeys.all, 'recents'] as const,
  recentDestinations: () => [...placesKeys.recentsRoot(), 'destinations'] as const,
  recentParking: () => [...placesKeys.recentsRoot(), 'parking'] as const,
};

/** Destination-scoped recommendations — user/session private; clear on logout. */
export type RecommendationListFilters = {
  destKey: string;
  radiusMeters: number;
  limit: number;
  includeCommunity: boolean;
  includeMunicipal: boolean;
};

export const recommendationKeys = {
  all: ['recommendations'] as const,
  lists: () => [...recommendationKeys.all, 'list'] as const,
  list: (filters: RecommendationListFilters) =>
    [...recommendationKeys.lists(), filters] as const,
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
