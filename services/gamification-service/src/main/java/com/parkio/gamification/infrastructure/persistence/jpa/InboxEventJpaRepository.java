package com.parkio.gamification.infrastructure.persistence.jpa;

import com.parkio.gamification.infrastructure.persistence.entity.InboxEventEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventJpaRepository extends JpaRepository<InboxEventEntity, UUID> {
}
