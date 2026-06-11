import type { AxiosInstance } from 'axios';
import type {
  GamificationAccessPolicy,
  GamificationProgress,
  LeaderboardEntry,
  LevelRule,
  LevelStanding,
  PointsSummary,
} from '@parkio/types';

export function createGamificationApi(client: AxiosInstance) {
  return {
    getMyProgress(): Promise<GamificationProgress> {
      return client.get<GamificationProgress>('/gamification/me/progress').then((r) => r.data);
    },

    /** Point total plus the most recent 50 ledger entries. */
    getMyPoints(): Promise<PointsSummary> {
      return client.get<PointsSummary>('/gamification/me/points').then((r) => r.data);
    },

    getMyLevel(): Promise<LevelStanding> {
      return client.get<LevelStanding>('/gamification/me/level').then((r) => r.data);
    },

    getMyAccessPolicy(): Promise<GamificationAccessPolicy> {
      return client
        .get<GamificationAccessPolicy>('/gamification/me/access-policy')
        .then((r) => r.data);
    },

    getLevels(): Promise<LevelRule[]> {
      return client.get<LevelRule[]>('/gamification/levels').then((r) => r.data);
    },

    /** `limit` must be 1–100 (backend default 20 when omitted). */
    getLeaderboard(limit?: number): Promise<LeaderboardEntry[]> {
      return client
        .get<LeaderboardEntry[]>('/gamification/leaderboard', {
          params: limit === undefined ? undefined : { limit },
        })
        .then((r) => r.data);
    },
  };
}

export type GamificationApi = ReturnType<typeof createGamificationApi>;
