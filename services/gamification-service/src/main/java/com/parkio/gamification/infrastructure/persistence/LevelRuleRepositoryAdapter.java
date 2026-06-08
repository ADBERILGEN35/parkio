package com.parkio.gamification.infrastructure.persistence;

import com.parkio.gamification.application.port.LevelRuleRepository;
import com.parkio.gamification.domain.LevelRule;
import com.parkio.gamification.infrastructure.persistence.jpa.LevelRuleJpaRepository;
import com.parkio.gamification.infrastructure.persistence.mapper.GamificationPersistenceMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/** Adapts the {@link LevelRuleRepository} port to Spring Data JPA. */
@Component
public class LevelRuleRepositoryAdapter implements LevelRuleRepository {

    private final LevelRuleJpaRepository jpa;

    public LevelRuleRepositoryAdapter(LevelRuleJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<LevelRule> findAll() {
        return jpa.findAll().stream().map(GamificationPersistenceMapper::toDomain).toList();
    }
}
