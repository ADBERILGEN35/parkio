/** What caused a point change — mirrors gamification-service `PointSourceType`. */
export const POINT_SOURCE_TYPES = [
  'PARKING_UPLOAD',
  'PARKING_VERIFIED',
  'PARKING_CLAIMED',
  'PARKING_FILLED_BY_USER',
  'PENALTY_FAKE',
  'PENALTY_SPAM',
  'PENALTY_ILLEGAL_RISK',
] as const;

export type PointSourceType = (typeof POINT_SOURCE_TYPES)[number];

/** Whether a transaction added or removed points — mirrors `PointDirection`. */
export const POINT_DIRECTIONS = ['EARNED', 'DEDUCTED'] as const;

export type PointDirection = (typeof POINT_DIRECTIONS)[number];

/** `GET /gamification/me/progress` — mirrors `ProgressResponse`. */
export interface GamificationProgress {
  userId: string;
  totalPoints: number;
  currentLevel: number;
  updatedAt: string;
}

/** One ledger entry in the points history — mirrors `PointsResponse.Entry`. */
export interface PointTransactionEntry {
  sourceType: PointSourceType;
  direction: PointDirection;
  points: number;
  relatedSpotId: string | null;
  createdAt: string;
}

/** `GET /gamification/me/points` — mirrors `PointsResponse` (most recent 50 entries). */
export interface PointsSummary {
  userId: string;
  totalPoints: number;
  recentTransactions: PointTransactionEntry[];
}

/**
 * `GET /gamification/me/level` — mirrors `LevelResponse`.
 * `nextLevelMinPoints`/`pointsToNextLevel` are null at the maximum level.
 */
export interface LevelStanding {
  userId: string;
  currentLevel: number;
  totalPoints: number;
  currentLevelMinPoints: number;
  nextLevelMinPoints: number | null;
  pointsToNextLevel: number | null;
}

/** `GET /gamification/me/access-policy` — mirrors `AccessPolicyResponse`. */
export interface GamificationAccessPolicy {
  userId: string;
  currentLevel: number;
  searchRadiusMeters: number;
  resultLimit: number;
  dailyViewLimit: number;
  verifiedSpotPriority: boolean;
  notificationPriority: boolean;
}

/**
 * One level definition (`GET /gamification/levels`) — mirrors `LevelRuleResponse`.
 * `maxPoints` is null for the open-ended top level.
 */
export interface LevelRule {
  level: number;
  minPoints: number;
  maxPoints: number | null;
  searchRadiusMeters: number;
  resultLimit: number;
  dailyViewLimit: number;
  verifiedSpotPriority: boolean;
  notificationPriority: boolean;
}

/**
 * One ranked row (`GET /gamification/leaderboard`) — mirrors `LeaderboardEntryResponse`.
 * Only the platform user id is exposed — no display names in this response.
 */
export interface LeaderboardEntry {
  rank: number;
  userId: string;
  totalPoints: number;
  currentLevel: number;
}

/** Backend DTO name aliases. */
export type ProgressResponse = GamificationProgress;
export type PointsResponse = PointsSummary;
export type LevelResponse = LevelStanding;
export type AccessPolicyResponse = GamificationAccessPolicy;
export type LevelRuleResponse = LevelRule;
export type LeaderboardEntryResponse = LeaderboardEntry;

/** Mirrors gamification-service leaderboard settings (default-limit / max-limit). */
export const LEADERBOARD_DEFAULT_LIMIT = 20;
export const LEADERBOARD_MAX_LIMIT = 100;
