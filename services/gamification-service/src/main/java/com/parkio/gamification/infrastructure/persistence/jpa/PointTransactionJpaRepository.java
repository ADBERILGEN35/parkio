package com.parkio.gamification.infrastructure.persistence.jpa;

import com.parkio.gamification.infrastructure.persistence.entity.PointTransactionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointTransactionJpaRepository extends JpaRepository<PointTransactionEntity, UUID> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<PointTransactionEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
