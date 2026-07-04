package com.parkio.gamification.infrastructure.persistence.jpa;

import com.parkio.gamification.infrastructure.persistence.entity.TrustRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrustRuleJpaRepository extends JpaRepository<TrustRuleEntity, String> {
}
