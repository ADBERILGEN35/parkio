package com.parkio.analytics.application.port;

import com.parkio.analytics.domain.AnalyticsMetricType;
import com.parkio.analytics.domain.ParkingAnalyticsSnapshot;
import java.util.List;
import java.util.Optional;

/** Persistence port for {@link ParkingAnalyticsSnapshot}. */
public interface ParkingAnalyticsSnapshotRepository {

    ParkingAnalyticsSnapshot save(ParkingAnalyticsSnapshot snapshot);

    Optional<ParkingAnalyticsSnapshot> findByMetricType(AnalyticsMetricType metricType);

    List<ParkingAnalyticsSnapshot> findAll();
}
