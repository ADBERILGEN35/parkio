package com.parkio.moderation.infrastructure.persistence.jpa;

import com.parkio.moderation.infrastructure.persistence.entity.ModerationDecisionEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModerationDecisionJpaRepository extends JpaRepository<ModerationDecisionEntity, UUID> {
}
