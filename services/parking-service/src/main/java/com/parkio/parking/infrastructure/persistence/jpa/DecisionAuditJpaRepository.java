package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.DecisionAuditEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access for append-only {@code decision_audit}. */
public interface DecisionAuditJpaRepository extends JpaRepository<DecisionAuditEntity, UUID> {

    List<DecisionAuditEntity> findByParkingSpotIdOrderByEvaluatedAtAsc(UUID parkingSpotId);

    List<DecisionAuditEntity> findByEvaluationIdOrderByEvaluatedAtAsc(UUID evaluationId);

    List<DecisionAuditEntity> findByPolicyVersionOrderByEvaluatedAtAsc(String policyVersion);

    List<DecisionAuditEntity> findByEvaluatedAtGreaterThanEqualAndEvaluatedAtLessThanOrderByEvaluatedAtAsc(
            Instant fromInclusive, Instant toExclusive);

    Optional<DecisionAuditEntity> findFirstByEvaluationIdAndPolicyVersionAndExecutionModeAndAuthorityAppliedTrue(
            UUID evaluationId, String policyVersion, String executionMode);
}