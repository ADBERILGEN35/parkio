package com.parkio.gamification.infrastructure.persistence.jpa;

import com.parkio.gamification.infrastructure.persistence.entity.PenaltyRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PenaltyRuleJpaRepository extends JpaRepository<PenaltyRuleEntity, String> {
}
