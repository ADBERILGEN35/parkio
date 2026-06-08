package com.parkio.moderation.infrastructure.persistence;

import com.parkio.moderation.application.port.AppealRepository;
import com.parkio.moderation.domain.Appeal;
import com.parkio.moderation.infrastructure.persistence.jpa.AppealJpaRepository;
import com.parkio.moderation.infrastructure.persistence.mapper.ModerationPersistenceMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link AppealRepository} port to Spring Data JPA. */
@Component
public class AppealRepositoryAdapter implements AppealRepository {

    private final AppealJpaRepository jpa;

    public AppealRepositoryAdapter(AppealJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Appeal save(Appeal appeal) {
        return ModerationPersistenceMapper.toDomain(jpa.save(ModerationPersistenceMapper.toEntity(appeal)));
    }

    @Override
    public Optional<Appeal> findById(UUID id) {
        return jpa.findById(id).map(ModerationPersistenceMapper::toDomain);
    }

    @Override
    public boolean existsByCaseIdAndAppealUserId(UUID caseId, UUID appealUserId) {
        return jpa.existsByCaseIdAndAppealUserId(caseId, appealUserId);
    }

    @Override
    public List<Appeal> findRecent() {
        return jpa.findTop200ByOrderByCreatedAtDesc().stream()
                .map(ModerationPersistenceMapper::toDomain)
                .toList();
    }
}
