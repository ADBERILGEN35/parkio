package com.parkio.gamification.infrastructure.persistence;

import com.parkio.gamification.application.port.TrustRuleRepository;
import com.parkio.gamification.domain.TrustRule;
import com.parkio.gamification.infrastructure.persistence.jpa.TrustRuleJpaRepository;
import com.parkio.gamification.infrastructure.persistence.mapper.GamificationPersistenceMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Adapts the {@link TrustRuleRepository} port to Spring Data JPA. */
@Component
public class TrustRuleRepositoryAdapter implements TrustRuleRepository {

    private final TrustRuleJpaRepository jpa;

    public TrustRuleRepositoryAdapter(TrustRuleJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<TrustRule> findByRuleKey(String ruleKey) {
        return jpa.findById(ruleKey).map(GamificationPersistenceMapper::toDomain);
    }
}
