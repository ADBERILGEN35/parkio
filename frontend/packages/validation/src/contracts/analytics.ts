import { z } from 'zod';
import {
  ANALYTICS_METRIC_TYPES,
  type AnalyticsMetric,
  type AnalyticsOverview,
  type DailyAnalytics,
  type ParkingAnalytics,
  type UserAnalytics,
} from '@parkio/types';
import { integerSchema, localDateSchema, uuidSchema } from './primitives';

export const analyticsOverviewResponseSchema = z
  .object({
    totalParkingCreated: integerSchema,
    totalParkingVerified: integerSchema,
    totalParkingClaimed: integerSchema,
    totalParkingRejected: integerSchema,
    totalPointsEarned: integerSchema,
    totalLevelUps: integerSchema,
    totalNotificationsCreated: integerSchema,
  })
  .strip() satisfies z.ZodType<AnalyticsOverview>;

export const dailyAnalyticsResponseSchema = z
  .object({
    date: localDateSchema,
    metricType: z.enum(ANALYTICS_METRIC_TYPES),
    eventCount: integerSchema,
    sumValue: integerSchema,
  })
  .strip() satisfies z.ZodType<DailyAnalytics>;

export const userAnalyticsResponseSchema = z
  .object({
    userId: uuidSchema,
    metricType: z.enum(ANALYTICS_METRIC_TYPES),
    eventCount: integerSchema,
    sumValue: integerSchema,
  })
  .strip() satisfies z.ZodType<UserAnalytics>;

export const parkingAnalyticsResponseSchema = z
  .object({
    metricType: z.enum(ANALYTICS_METRIC_TYPES),
    eventCount: integerSchema,
    sumValue: integerSchema,
  })
  .strip() satisfies z.ZodType<ParkingAnalytics>;

export const analyticsMetricResponseSchema = z
  .object({
    metricType: z.enum(ANALYTICS_METRIC_TYPES),
    totalCount: integerSchema,
    totalValue: integerSchema,
  })
  .strip() satisfies z.ZodType<AnalyticsMetric>;

export const dailyAnalyticsListResponseSchema = z.array(dailyAnalyticsResponseSchema);
export const userAnalyticsListResponseSchema = z.array(userAnalyticsResponseSchema);
export const parkingAnalyticsListResponseSchema = z.array(parkingAnalyticsResponseSchema);
export const analyticsMetricListResponseSchema = z.array(analyticsMetricResponseSchema);
