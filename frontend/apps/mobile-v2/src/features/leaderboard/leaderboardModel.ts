import type { LeaderboardEntry, LevelStanding } from '@parkio/types';

/** Canonical reward amounts from gamification-service V4 seed (not speculative UI copy). */
export const REWARD_HINTS = [
  { points: 5, key: 'leaderboard.how.upload' as const },
  { points: 20, key: 'leaderboard.how.verifiedOwner' as const },
  { points: 5, key: 'leaderboard.how.verifiedVerifier' as const },
  { points: 30, key: 'leaderboard.how.claimedOwner' as const },
  { points: 10, key: 'leaderboard.how.claimedClaimer' as const },
] as const;

export type TopContributorMode = 'empty' | 'one' | 'two' | 'podium';

export function topContributorMode(count: number): TopContributorMode {
  if (count <= 0) return 'empty';
  if (count === 1) return 'one';
  if (count === 2) return 'two';
  return 'podium';
}

export function partitionLeaderboard(entries: LeaderboardEntry[], selfId?: string) {
  return {
    podium: entries.slice(0, 3),
    rest: entries.slice(3),
    selfEntry: selfId ? (entries.find((entry) => entry.userId === selfId) ?? null) : null,
  };
}

export function anonymousDriverLabel(userId: string, format: (shortId: string) => string): string {
  const shortId = userId.replace(/-/g, '').slice(0, 4).toUpperCase();
  return format(shortId);
}

export function truncateDisplayName(name: string, max = 18): string {
  const trimmed = name.trim();
  if (trimmed.length <= max) return trimmed;
  return `${trimmed.slice(0, Math.max(1, max - 1)).trimEnd()}...`;
}

/** Level progress from GET /gamification/me/level — omit when backend has no next level. */
export function standingProgress(standing: LevelStanding | null | undefined): {
  fraction: number | null;
  pointsToNext: number | null;
  nextMin: number | null;
} {
  if (!standing || standing.nextLevelMinPoints == null || standing.pointsToNextLevel == null) {
    return { fraction: null, pointsToNext: null, nextMin: null };
  }
  const span = standing.nextLevelMinPoints - standing.currentLevelMinPoints;
  const fraction =
    span > 0
      ? Math.min(1, Math.max(0, (standing.totalPoints - standing.currentLevelMinPoints) / span))
      : 1;
  return {
    fraction,
    pointsToNext: Math.max(0, standing.pointsToNextLevel),
    nextMin: standing.nextLevelMinPoints,
  };
}
