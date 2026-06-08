package com.parkio.analytics.presentation.dto;

import com.parkio.analytics.domain.AnalyticsMetric;

/** A single metric's lifetime totals. */
public record MetricResponse(String metricType, long totalCount, long totalValue) {

    public static MetricResponse from(AnalyticsMetric m) {
        return new MetricResponse(m.metricType().name(), m.totalCount(), m.totalValue());
    }
}
