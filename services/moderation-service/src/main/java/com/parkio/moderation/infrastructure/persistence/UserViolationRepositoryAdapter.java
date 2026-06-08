package com.parkio.moderation.infrastructure.persistence;

import com.parkio.moderation.application.port.UserViolationRepository;
import com.parkio.moderation.domain.UserViolation;
import com.parkio.moderation.infrastructure.persistence.jpa.UserViolationJpaRepository;
import com.parkio.moderation.infrastructure.persistence.mapper.ModerationPersistenceMapper;
import org.springframework.stereotype.Component;

/** Adapts the {@link UserViolationRepository} port to Spring Data JPA. */
@Component
public class UserViolationRepositoryAdapter implements UserViolationRepository {

    private final UserViolationJpaRepository jpa;

    public UserViolationRepositoryAdapter(UserViolationJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public UserViolation save(UserViolation violation) {
        return ModerationPersistenceMapper.toDomain(jpa.save(ModerationPersistenceMapper.toEntity(violation)));
    }
}
