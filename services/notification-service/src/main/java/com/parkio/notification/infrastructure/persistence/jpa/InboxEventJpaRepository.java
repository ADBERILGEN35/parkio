package com.parkio.notification.infrastructure.persistence.jpa;

import com.parkio.notification.infrastructure.persistence.entity.InboxEventEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventJpaRepository extends JpaRepository<InboxEventEntity, UUID> {
}
