package com.parkio.analytics.presentation.dto;

import com.parkio.analytics.domain.DailyAnalyticsSnapshot;
import java.time.LocalDate;

/** One day/metric data point of the time series. */
public record DailySnapshotResponse(LocalDate date, String metricType, long eventCount, long sumValue) {

    public static DailySnapshotResponse from(DailyAnalyticsSnapshot s) {
        return new DailySnapshotResponse(s.snapshotDate(), s.metricType().name(), s.eventCount(), s.sumValue());
    }
}
