package com.parkio.analytics.domain;

/**
 * A computed metric total ({@code totalCount} of events and {@code totalValue} of
 * their magnitudes) for one {@link AnalyticsMetricType}. A read-model value object
 * aggregated from snapshots — not persisted on its own.
 */
public record AnalyticsMetric(AnalyticsMetricType metricType, long totalCount, long totalValue) {

    public static AnalyticsMetric zero(AnalyticsMetricType metricType) {
        return new AnalyticsMetric(metricType, 0, 0);
    }
}
