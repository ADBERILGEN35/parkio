package com.parkio.analytics.application.port;

import com.parkio.analytics.domain.AnalyticsMetricType;
import com.parkio.analytics.domain.DailyAnalyticsSnapshot;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Persistence port for {@link DailyAnalyticsSnapshot}. */
public interface DailyAnalyticsSnapshotRepository {

    DailyAnalyticsSnapshot save(DailyAnalyticsSnapshot snapshot);

    Optional<DailyAnalyticsSnapshot> findByDateAndMetricType(LocalDate snapshotDate, AnalyticsMetricType metricType);

    /** All daily rows (bounded by days × metric types) — aggregated in memory for KPIs. */
    List<DailyAnalyticsSnapshot> findAll();
}
