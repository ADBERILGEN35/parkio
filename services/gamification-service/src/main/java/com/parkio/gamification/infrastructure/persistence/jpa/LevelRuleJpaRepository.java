package com.parkio.gamification.infrastructure.persistence.jpa;

import com.parkio.gamification.infrastructure.persistence.entity.LevelRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LevelRuleJpaRepository extends JpaRepository<LevelRuleEntity, Integer> {
}
