package com.parkio.gamification.infrastructure.persistence;

import com.parkio.gamification.application.port.PointTransactionRepository;
import com.parkio.gamification.domain.PointTransaction;
import com.parkio.gamification.infrastructure.persistence.jpa.PointTransactionJpaRepository;
import com.parkio.gamification.infrastructure.persistence.mapper.GamificationPersistenceMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** Adapts the {@link PointTransactionRepository} port to Spring Data JPA. */
@Component
public class PointTransactionRepositoryAdapter implements PointTransactionRepository {

    private final PointTransactionJpaRepository jpa;

    public PointTransactionRepositoryAdapter(PointTransactionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public PointTransaction save(PointTransaction transaction) {
        return GamificationPersistenceMapper.toDomain(jpa.save(GamificationPersistenceMapper.toEntity(transaction)));
    }

    @Override
    public boolean existsByIdempotencyKey(String idempotencyKey) {
        return jpa.existsByIdempotencyKey(idempotencyKey);
    }

    @Override
    public List<PointTransaction> findRecentByUserId(UUID userId, int limit) {
        return jpa.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit)).stream()
                .map(GamificationPersistenceMapper::toDomain)
                .toList();
    }
}
