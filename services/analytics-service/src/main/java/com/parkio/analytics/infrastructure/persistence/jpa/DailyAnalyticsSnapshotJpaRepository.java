package com.parkio.analytics.infrastructure.persistence.jpa;

import com.parkio.analytics.domain.AnalyticsMetricType;
import com.parkio.analytics.infrastructure.persistence.entity.DailyAnalyticsSnapshotEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyAnalyticsSnapshotJpaRepository extends JpaRepository<DailyAnalyticsSnapshotEntity, UUID> {

    Optional<DailyAnalyticsSnapshotEntity> findBySnapshotDateAndMetricType(
            LocalDate snapshotDate, AnalyticsMetricType metricType);

    List<DailyAnalyticsSnapshotEntity> findAllByOrderBySnapshotDateAscMetricTypeAsc();
}
