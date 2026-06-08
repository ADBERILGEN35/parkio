package com.parkio.analytics.infrastructure.persistence.jpa;

import com.parkio.analytics.infrastructure.persistence.entity.AnalyticsEventEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalyticsEventJpaRepository extends JpaRepository<AnalyticsEventEntity, UUID> {
}
