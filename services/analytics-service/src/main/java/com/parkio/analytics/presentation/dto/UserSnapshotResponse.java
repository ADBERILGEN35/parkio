package com.parkio.analytics.presentation.dto;

import com.parkio.analytics.domain.UserAnalyticsSnapshot;
import java.util.UUID;

/** One user/metric aggregate row. */
public record UserSnapshotResponse(UUID userId, String metricType, long eventCount, long sumValue) {

    public static UserSnapshotResponse from(UserAnalyticsSnapshot s) {
        return new UserSnapshotResponse(s.userId(), s.metricType().name(), s.eventCount(), s.sumValue());
    }
}
