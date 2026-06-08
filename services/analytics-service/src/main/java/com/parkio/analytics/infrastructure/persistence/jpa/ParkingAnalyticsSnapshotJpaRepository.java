package com.parkio.analytics.infrastructure.persistence.jpa;

import com.parkio.analytics.domain.AnalyticsMetricType;
import com.parkio.analytics.infrastructure.persistence.entity.ParkingAnalyticsSnapshotEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParkingAnalyticsSnapshotJpaRepository
        extends JpaRepository<ParkingAnalyticsSnapshotEntity, UUID> {

    Optional<ParkingAnalyticsSnapshotEntity> findByMetricType(AnalyticsMetricType metricType);
}
