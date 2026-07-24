import type { AxiosInstance } from 'axios';
import type {
  GamificationAccessPolicy,
  GamificationProgress,
  LeaderboardEntry,
  LevelRule,
  LevelStanding,
  PointsSummary,
} from '@parkio/types';
import type { RequestOptions } from './request-options';

export function createGamificationApi(client: AxiosInstance) {
  return {
    getMyProgress(options?: RequestOptions): Promise<GamificationProgress> {
      return client
        .get<GamificationProgress>('/gamification/me/progress', { signal: options?.signal })
        .then((r) => r.data);
    },

    /** Point total plus the most recent 50 ledger entries. */
    getMyPoints(options?: RequestOptions): Promise<PointsSummary> {
      return client
        .get<PointsSummary>('/gamification/me/points', { signal: options?.signal })
        .then((r) => r.data);
    },

    getMyLevel(options?: RequestOptions): Promise<LevelStanding> {
      return client
        .get<LevelStanding>('/gamification/me/level', { signal: options?.signal })
        .then((r) => r.data);
    },

    getMyAccessPolicy(options?: RequestOptions): Promise<GamificationAccessPolicy> {
      return client
        .get<GamificationAccessPolicy>('/gamification/me/access-policy', {
          signal: options?.signal,
        })
        .then((r) => r.data);
    },

    getLevels(options?: RequestOptions): Promise<LevelRule[]> {
      return client
        .get<LevelRule[]>('/gamification/levels', { signal: options?.signal })
        .then((r) => r.data);
    },

    /** `limit` must be 1–100 (backend default 20 when omitted). */
    getLeaderboard(limit?: number, options?: RequestOptions): Promise<LeaderboardEntry[]> {
      return client
        .get<LeaderboardEntry[]>('/gamification/leaderboard', {
          params: limit === undefined ? undefined : { limit },
          signal: options?.signal,
        })
        .then((r) => r.data);
    },
  };
}

export type GamificationApi = ReturnType<typeof createGamificationApi>;
