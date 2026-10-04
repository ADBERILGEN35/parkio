package com.parkio.gamification.infrastructure.persistence;

import com.parkio.gamification.application.port.TrustScoreRepository;
import com.parkio.gamification.domain.TrustScore;
import com.parkio.gamification.infrastructure.persistence.jpa.TrustScoreJpaRepository;
import com.parkio.gamification.infrastructure.persistence.mapper.GamificationPersistenceMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link TrustScoreRepository} port to Spring Data JPA. */
@Component
public class TrustScoreRepositoryAdapter implements TrustScoreRepository {

    private final TrustScoreJpaRepository jpa;

    public TrustScoreRepositoryAdapter(TrustScoreJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public TrustScore save(TrustScore trustScore) {
        // Flushed so the returned version is the one this change produced; events carry it (U12).
        return GamificationPersistenceMapper.toDomain(
                jpa.saveAndFlush(GamificationPersistenceMapper.toEntity(trustScore)));
    }

    @Override
    public Optional<TrustScore> findByUserId(UUID userId) {
        return jpa.findById(userId).map(GamificationPersistenceMapper::toDomain);
    }
}
