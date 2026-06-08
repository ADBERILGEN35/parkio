package com.parkio.moderation.infrastructure.persistence;

import com.parkio.moderation.application.port.ModerationCaseRepository;
import com.parkio.moderation.domain.ModerationCase;
import com.parkio.moderation.domain.ModerationStatus;
import com.parkio.moderation.domain.ModerationTargetType;
import com.parkio.moderation.infrastructure.persistence.jpa.ModerationCaseJpaRepository;
import com.parkio.moderation.infrastructure.persistence.mapper.ModerationPersistenceMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link ModerationCaseRepository} port to Spring Data JPA. */
@Component
public class ModerationCaseRepositoryAdapter implements ModerationCaseRepository {

    private static final List<ModerationStatus> ACTIVE_STATUSES =
            List.of(ModerationStatus.OPEN, ModerationStatus.IN_REVIEW);

    private final ModerationCaseJpaRepository jpa;

    public ModerationCaseRepositoryAdapter(ModerationCaseJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ModerationCase save(ModerationCase moderationCase) {
        return ModerationPersistenceMapper.toDomain(jpa.save(ModerationPersistenceMapper.toEntity(moderationCase)));
    }

    @Override
    public Optional<ModerationCase> findById(UUID id) {
        return jpa.findById(id).map(ModerationPersistenceMapper::toDomain);
    }

    @Override
    public Optional<ModerationCase> findActiveByTarget(ModerationTargetType targetType, UUID targetId) {
        return jpa.findFirstByTargetTypeAndTargetIdAndStatusIn(targetType, targetId, ACTIVE_STATUSES)
                .map(ModerationPersistenceMapper::toDomain);
    }

    @Override
    public List<ModerationCase> findRecent() {
        return jpa.findTop200ByOrderByOpenedAtDesc().stream()
                .map(ModerationPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<ModerationCase> findByStatus(ModerationStatus status) {
        return jpa.findByStatusOrderByOpenedAtDesc(status).stream()
                .map(ModerationPersistenceMapper::toDomain)
                .toList();
    }
}
