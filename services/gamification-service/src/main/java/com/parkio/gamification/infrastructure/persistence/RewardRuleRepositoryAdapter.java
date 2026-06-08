package com.parkio.gamification.infrastructure.persistence;

import com.parkio.gamification.application.port.RewardRuleRepository;
import com.parkio.gamification.domain.RewardRule;
import com.parkio.gamification.infrastructure.persistence.jpa.RewardRuleJpaRepository;
import com.parkio.gamification.infrastructure.persistence.mapper.GamificationPersistenceMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Adapts the {@link RewardRuleRepository} port to Spring Data JPA. */
@Component
public class RewardRuleRepositoryAdapter implements RewardRuleRepository {

    private final RewardRuleJpaRepository jpa;

    public RewardRuleRepositoryAdapter(RewardRuleJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<RewardRule> findByRuleKey(String ruleKey) {
        return jpa.findById(ruleKey).map(GamificationPersistenceMapper::toDomain);
    }
}
