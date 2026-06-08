package com.parkio.analytics.presentation.dto;

import com.parkio.analytics.domain.ParkingAnalyticsSnapshot;

/** One parking-funnel metric aggregate. */
public record ParkingSnapshotResponse(String metricType, long eventCount, long sumValue) {

    public static ParkingSnapshotResponse from(ParkingAnalyticsSnapshot s) {
        return new ParkingSnapshotResponse(s.metricType().name(), s.eventCount(), s.sumValue());
    }
}
