package com.parkio.aivalidation.infrastructure.persistence.jpa;

import com.parkio.aivalidation.infrastructure.persistence.entity.InboxEventEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventJpaRepository extends JpaRepository<InboxEventEntity, UUID> {
}
