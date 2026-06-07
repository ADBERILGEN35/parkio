package com.parkio.user.infrastructure.persistence;

import com.parkio.user.application.port.UserTrustScoreHistoryRepository;
import com.parkio.user.domain.UserTrustScoreHistory;
import com.parkio.user.infrastructure.persistence.jpa.UserTrustScoreHistoryJpaRepository;
import com.parkio.user.infrastructure.persistence.mapper.UserPersistenceMapper;
import org.springframework.stereotype.Component;

/** Adapts the {@link UserTrustScoreHistoryRepository} port to Spring Data JPA. */
@Component
public class UserTrustScoreHistoryRepositoryAdapter implements UserTrustScoreHistoryRepository {

    private final UserTrustScoreHistoryJpaRepository jpa;

    public UserTrustScoreHistoryRepositoryAdapter(UserTrustScoreHistoryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public UserTrustScoreHistory save(UserTrustScoreHistory history) {
        return UserPersistenceMapper.toDomain(jpa.save(UserPersistenceMapper.toEntity(history)));
    }
}
