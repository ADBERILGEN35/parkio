package com.parkio.analytics.infrastructure.persistence.jpa;

import com.parkio.analytics.domain.AnalyticsMetricType;
import com.parkio.analytics.infrastructure.persistence.entity.UserAnalyticsSnapshotEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAnalyticsSnapshotJpaRepository extends JpaRepository<UserAnalyticsSnapshotEntity, UUID> {

    Optional<UserAnalyticsSnapshotEntity> findByUserIdAndMetricType(UUID userId, AnalyticsMetricType metricType);

    List<UserAnalyticsSnapshotEntity> findByUserId(UUID userId);
}
