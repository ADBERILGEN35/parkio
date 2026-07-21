import type { LeaderboardEntry, LevelStanding } from '@parkio/types';
import {
  REWARD_HINTS,
  anonymousDriverLabel,
  partitionLeaderboard,
  standingProgress,
  topContributorMode,
  truncateDisplayName,
} from '../leaderboardModel';

function entry(overrides: Partial<LeaderboardEntry> & Pick<LeaderboardEntry, 'userId' | 'rank'>): LeaderboardEntry {
  return {
    totalPoints: 10,
    currentLevel: 1,
    ...overrides,
  };
}

describe('leaderboardModel', () => {
  it('maps contributor counts to presentation modes', () => {
    expect(topContributorMode(0)).toBe('empty');
    expect(topContributorMode(1)).toBe('one');
    expect(topContributorMode(2)).toBe('two');
    expect(topContributorMode(3)).toBe('podium');
    expect(topContributorMode(10)).toBe('podium');
  });

  it('partitions podium, rest, and self for 0–4 entries', () => {
    expect(partitionLeaderboard([], 'me')).toEqual({ podium: [], rest: [], selfEntry: null });

    const one = [entry({ userId: 'me', rank: 1, totalPoints: 30 })];
    expect(partitionLeaderboard(one, 'me').podium).toHaveLength(1);
    expect(partitionLeaderboard(one, 'me').rest).toHaveLength(0);
    expect(partitionLeaderboard(one, 'me').selfEntry?.userId).toBe('me');

    const two = [
      entry({ userId: 'a', rank: 1 }),
      entry({ userId: 'me', rank: 2 }),
    ];
    expect(partitionLeaderboard(two, 'me').podium).toHaveLength(2);
    expect(partitionLeaderboard(two, 'me').rest).toHaveLength(0);

    const four = [
      entry({ userId: 'a', rank: 1 }),
      entry({ userId: 'b', rank: 2 }),
      entry({ userId: 'c', rank: 3 }),
      entry({ userId: 'me', rank: 4 }),
    ];
    const parts = partitionLeaderboard(four, 'me');
    expect(parts.podium.map((e) => e.userId)).toEqual(['a', 'b', 'c']);
    expect(parts.rest.map((e) => e.userId)).toEqual(['me']);
    expect(parts.selfEntry?.rank).toBe(4);
  });

  it('builds anonymous labels from user ids', () => {
    expect(anonymousDriverLabel('abcd-efgh-ijkl', (id) => `Driver ${id}`)).toBe('Driver ABCD');
  });

  it('truncates long display names safely', () => {
    expect(truncateDisplayName('Short')).toBe('Short');
    expect(truncateDisplayName('A very long display name indeed').endsWith('...')).toBe(true);
    expect(truncateDisplayName('A very long display name indeed').length).toBeLessThanOrEqual(20);
  });

  it('computes standing progress from LevelStanding and omits when maxed', () => {
    const standing: LevelStanding = {
      userId: 'me',
      currentLevel: 1,
      totalPoints: 30,
      currentLevelMinPoints: 0,
      nextLevelMinPoints: 50,
      pointsToNextLevel: 20,
    };
    expect(standingProgress(standing)).toEqual({
      fraction: 0.6,
      pointsToNext: 20,
      nextMin: 50,
    });
    expect(
      standingProgress({
        ...standing,
        nextLevelMinPoints: null,
        pointsToNextLevel: null,
      }),
    ).toEqual({ fraction: null, pointsToNext: null, nextMin: null });
    expect(standingProgress(null)).toEqual({ fraction: null, pointsToNext: null, nextMin: null });
  });

  it('keeps reward hints aligned with V4 seed amounts', () => {
    expect(REWARD_HINTS.map((hint) => hint.points)).toEqual([5, 20, 5, 30, 10]);
  });

  it('tolerates null-ish partial entry fields without throwing', () => {
    const partial = entry({
      userId: 'x',
      rank: 1,
      totalPoints: Number.NaN,
      currentLevel: 0,
    });
    expect(() => partitionLeaderboard([partial], undefined)).not.toThrow();
    expect(partitionLeaderboard([partial]).selfEntry).toBeNull();
  });
});
