package com.parkio.auth.infrastructure.persistence.jpa;

import com.parkio.auth.infrastructure.persistence.entity.InboxEventEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventJpaRepository extends JpaRepository<InboxEventEntity, UUID> {
}
