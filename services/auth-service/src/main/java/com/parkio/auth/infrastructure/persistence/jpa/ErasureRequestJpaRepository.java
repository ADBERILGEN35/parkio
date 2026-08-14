package com.parkio.auth.infrastructure.persistence.jpa;

import com.parkio.auth.infrastructure.persistence.entity.ErasureRequestEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ErasureRequestJpaRepository extends JpaRepository<ErasureRequestEntity, UUID> {

    Optional<ErasureRequestEntity> findFirstByAuthUserIdOrderByRequestedAtDesc(UUID authUserId);

    List<ErasureRequestEntity> findByStatusIn(List<String> statuses);

    @Query("""
            SELECT COUNT(r) FROM ErasureRequestEntity r
            WHERE r.status IN ('REQUESTED', 'IN_PROGRESS', 'FAILED_RETRYING')
              AND r.requestedAt < :before
            """)
    long countStuckBefore(@Param("before") Instant before);
}
