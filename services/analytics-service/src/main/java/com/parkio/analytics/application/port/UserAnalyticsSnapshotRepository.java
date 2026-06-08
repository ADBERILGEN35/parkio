package com.parkio.analytics.application.port;

import com.parkio.analytics.domain.AnalyticsMetricType;
import com.parkio.analytics.domain.UserAnalyticsSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link UserAnalyticsSnapshot}. */
public interface UserAnalyticsSnapshotRepository {

    UserAnalyticsSnapshot save(UserAnalyticsSnapshot snapshot);

    Optional<UserAnalyticsSnapshot> findByUserIdAndMetricType(UUID userId, AnalyticsMetricType metricType);

    List<UserAnalyticsSnapshot> findByUserId(UUID userId);
}
