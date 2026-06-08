package com.parkio.notification.infrastructure.persistence.jpa;

import com.parkio.notification.infrastructure.persistence.entity.OutboxEventEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {
}
