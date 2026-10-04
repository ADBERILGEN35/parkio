package com.parkio.gamification.infrastructure.persistence;

import com.parkio.gamification.application.port.UserLevelProgressRepository;
import com.parkio.gamification.domain.UserLevelProgress;
import com.parkio.gamification.infrastructure.persistence.jpa.UserLevelProgressJpaRepository;
import com.parkio.gamification.infrastructure.persistence.mapper.GamificationPersistenceMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** Adapts the {@link UserLevelProgressRepository} port to Spring Data JPA. */
@Component
public class UserLevelProgressRepositoryAdapter implements UserLevelProgressRepository {

    private final UserLevelProgressJpaRepository jpa;

    public UserLevelProgressRepositoryAdapter(UserLevelProgressJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public UserLevelProgress save(UserLevelProgress progress) {
        // Flushed so the returned version is the one this change produced; events carry it (U12).
        return GamificationPersistenceMapper.toDomain(
                jpa.saveAndFlush(GamificationPersistenceMapper.toEntity(progress)));
    }

    @Override
    public Optional<UserLevelProgress> findByUserId(UUID userId) {
        return jpa.findById(userId).map(GamificationPersistenceMapper::toDomain);
    }

    @Override
    public List<UserLevelProgress> findTopByPoints(int limit) {
        return jpa.findByOrderByTotalPointsDesc(PageRequest.of(0, limit)).stream()
                .map(GamificationPersistenceMapper::toDomain)
                .toList();
    }
}
