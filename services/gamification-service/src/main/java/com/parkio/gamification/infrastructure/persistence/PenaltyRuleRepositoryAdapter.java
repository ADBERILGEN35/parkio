package com.parkio.gamification.infrastructure.persistence;

import com.parkio.gamification.application.port.PenaltyRuleRepository;
import com.parkio.gamification.domain.PenaltyRule;
import com.parkio.gamification.infrastructure.persistence.jpa.PenaltyRuleJpaRepository;
import com.parkio.gamification.infrastructure.persistence.mapper.GamificationPersistenceMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Adapts the {@link PenaltyRuleRepository} port to Spring Data JPA. */
@Component
public class PenaltyRuleRepositoryAdapter implements PenaltyRuleRepository {

    private final PenaltyRuleJpaRepository jpa;

    public PenaltyRuleRepositoryAdapter(PenaltyRuleJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<PenaltyRule> findByRuleKey(String ruleKey) {
        return jpa.findById(ruleKey).map(GamificationPersistenceMapper::toDomain);
    }
}
