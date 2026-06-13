/**
 * Analytics contracts — mirrors analytics-service presentation DTOs exactly.
 * All endpoints are read-only GETs without query parameters (no date-range or
 * metric filters exist). `TimeGranularity` exists in the backend domain but is
 * never returned or accepted, so it is not typed here.
 */

/** Mirrors `AnalyticsMetricType` — the wire format is the enum name as a string. */
export const ANALYTICS_METRIC_TYPES = [
  'PARKING_CREATED',
  'PARKING_VERIFIED',
  'PARKING_CLAIMED',
  'PARKING_REJECTED',
  'POINTS_EARNED',
  'LEVEL_UP',
  'NOTIFICATION_CREATED',
] as const;

export type AnalyticsMetricType = (typeof ANALYTICS_METRIC_TYPES)[number];

/** Mirrors `OverviewResponse` — platform KPI overview (lifetime totals). */
export interface AnalyticsOverview {
  totalParkingCreated: number;
  totalParkingVerified: number;
  totalParkingClaimed: number;
  totalParkingRejected: number;
  totalPointsEarned: number;
  totalLevelUps: number;
  totalNotificationsCreated: number;
}

/** Backend DTO name alias. */
export type OverviewResponse = AnalyticsOverview;

/** Mirrors `DailySnapshotResponse` — one day/metric data point ("yyyy-MM-dd" date). */
export interface DailyAnalytics {
  date: string;
  metricType: AnalyticsMetricType;
  eventCount: number;
  sumValue: number;
}

/** Backend DTO name alias. */
export type DailySnapshotResponse = DailyAnalytics;

/** Mirrors `UserSnapshotResponse` — one user/metric aggregate row. */
export interface UserAnalytics {
  userId: string;
  metricType: AnalyticsMetricType;
  eventCount: number;
  sumValue: number;
}

/** Backend DTO name alias. */
export type UserSnapshotResponse = UserAnalytics;

/** Mirrors `ParkingSnapshotResponse` — one parking-funnel metric aggregate. */
export interface ParkingAnalytics {
  metricType: AnalyticsMetricType;
  eventCount: number;
  sumValue: number;
}

/** Backend DTO name alias. */
export type ParkingSnapshotResponse = ParkingAnalytics;

/** Mirrors `MetricResponse` — a single metric's lifetime totals. */
export interface AnalyticsMetric {
  metricType: AnalyticsMetricType;
  totalCount: number;
  totalValue: number;
}

/** Backend DTO name alias. */
export type MetricResponse = AnalyticsMetric;
