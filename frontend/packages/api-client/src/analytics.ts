import type { AxiosInstance } from 'axios';
import type {
  AnalyticsMetric,
  AnalyticsOverview,
  DailyAnalytics,
  ParkingAnalytics,
  UserAnalytics,
} from '@parkio/types';

/**
 * Analytics endpoints. None of them accept query parameters — there are no
 * date-range, granularity or metric filters on the backend.
 */
export function createAnalyticsApi(client: AxiosInstance) {
  return {
    getAnalyticsOverview(): Promise<AnalyticsOverview> {
      return client.get<AnalyticsOverview>('/analytics/overview').then((r) => r.data);
    },

    getDailyAnalytics(): Promise<DailyAnalytics[]> {
      return client.get<DailyAnalytics[]>('/analytics/daily').then((r) => r.data);
    },

    /**
     * The backend only allows fetching the authenticated user's own analytics —
     * any other id returns 403 FORBIDDEN, regardless of role.
     */
    getUserAnalytics(userId: string): Promise<UserAnalytics[]> {
      return client.get<UserAnalytics[]>(`/analytics/users/${userId}`).then((r) => r.data);
    },

    getParkingAnalytics(): Promise<ParkingAnalytics[]> {
      return client.get<ParkingAnalytics[]>('/analytics/parking').then((r) => r.data);
    },

    getAnalyticsMetrics(): Promise<AnalyticsMetric[]> {
      return client.get<AnalyticsMetric[]>('/analytics/metrics').then((r) => r.data);
    },
  };
}

export type AnalyticsApi = ReturnType<typeof createAnalyticsApi>;
