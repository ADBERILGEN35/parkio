package com.parkio.gamification.infrastructure.persistence.jpa;

import com.parkio.gamification.infrastructure.persistence.entity.RewardRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RewardRuleJpaRepository extends JpaRepository<RewardRuleEntity, String> {
}
