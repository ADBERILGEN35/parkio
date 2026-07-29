package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.OutcomeEvaluationTriggerEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutcomeEvaluationTriggerJpaRepository extends JpaRepository<OutcomeEvaluationTriggerEntity, UUID> {

    boolean existsByEvaluationId(UUID evaluationId);

    @Query(value = """
            SELECT * FROM outcome_evaluation_triggers
            WHERE processed = false AND dead_lettered = false
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutcomeEvaluationTriggerEntity> claimPendingBatch(@Param("limit") int limit);
}