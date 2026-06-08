package com.parkio.moderation.infrastructure.persistence;

import com.parkio.moderation.application.port.ModerationDecisionRepository;
import com.parkio.moderation.domain.ModerationDecision;
import com.parkio.moderation.infrastructure.persistence.jpa.ModerationDecisionJpaRepository;
import com.parkio.moderation.infrastructure.persistence.mapper.ModerationPersistenceMapper;
import org.springframework.stereotype.Component;

/** Adapts the {@link ModerationDecisionRepository} port to Spring Data JPA. */
@Component
public class ModerationDecisionRepositoryAdapter implements ModerationDecisionRepository {

    private final ModerationDecisionJpaRepository jpa;

    public ModerationDecisionRepositoryAdapter(ModerationDecisionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ModerationDecision save(ModerationDecision decision) {
        return ModerationPersistenceMapper.toDomain(jpa.save(ModerationPersistenceMapper.toEntity(decision)));
    }
}
