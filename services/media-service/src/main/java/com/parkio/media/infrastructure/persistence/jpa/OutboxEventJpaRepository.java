package com.parkio.media.infrastructure.persistence.jpa;

import com.parkio.media.infrastructure.persistence.entity.OutboxEventEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {
}
