package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.decision.audit.DecisionAuditRecord;
import com.parkio.parking.decision.port.DecisionAuditPort;
import com.parkio.parking.infrastructure.persistence.audit.DecisionAuditSnapshotMapper;
import com.parkio.parking.infrastructure.persistence.entity.DecisionAuditEntity;
import com.parkio.parking.infrastructure.persistence.jpa.DecisionAuditJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Append-only JPA adapter for {@link DecisionAuditPort}. Never updates existing rows. */
@Component
public class DecisionAuditRepositoryAdapter implements DecisionAuditPort {

    private final DecisionAuditJpaRepository jpa;
    private final DecisionAuditSnapshotMapper mapper;

    public DecisionAuditRepositoryAdapter(DecisionAuditJpaRepository jpa, ObjectMapper objectMapper) {
        this.jpa = Objects.requireNonNull(jpa, "jpa");
        this.mapper = new DecisionAuditSnapshotMapper(Objects.requireNonNull(objectMapper, "objectMapper"));
    }

    @Override
    public void append(DecisionAuditRecord record) {
        Objects.requireNonNull(record, "record");
        if (jpa.existsById(record.auditId())) {
            throw new IllegalStateException("decision audit records are immutable; refuse overwrite of " + record.auditId());
        }
        jpa.save(mapper.toEntity(record));
    }

    @Override
    public Optional<DecisionAuditRecord> findById(UUID auditId) {
        Objects.requireNonNull(auditId, "auditId");
        return jpa.findById(auditId).map(mapper::toDomain);
    }

    @Override
    public List<DecisionAuditRecord> findByParkingSpotId(UUID parkingSpotId) {
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        return mapAll(jpa.findByParkingSpotIdOrderByEvaluatedAtAsc(parkingSpotId));
    }

    @Override
    public List<DecisionAuditRecord> findByEvaluationId(UUID evaluationId) {
        Objects.requireNonNull(evaluationId, "evaluationId");
        return mapAll(jpa.findByEvaluationIdOrderByEvaluatedAtAsc(evaluationId));
    }

    @Override
    public List<DecisionAuditRecord> findByPolicyVersion(String policyVersion) {
        Objects.requireNonNull(policyVersion, "policyVersion");
        return mapAll(jpa.findByPolicyVersionOrderByEvaluatedAtAsc(policyVersion));
    }

    @Override
    public List<DecisionAuditRecord> findByEvaluatedAtBetween(Instant fromInclusive, Instant toExclusive) {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        return mapAll(jpa.findByEvaluatedAtGreaterThanEqualAndEvaluatedAtLessThanOrderByEvaluatedAtAsc(
                fromInclusive, toExclusive));
    }


    @Override
    public Optional<DecisionAuditRecord> findAuthoritativeApplied(UUID evaluationId, String policyVersion) {
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(policyVersion, "policyVersion");
        return jpa.findFirstByEvaluationIdAndPolicyVersionAndExecutionModeAndAuthorityAppliedTrue(
                        evaluationId, policyVersion, "AUTHORITATIVE")
                .map(mapper::toDomain);
    }
    private List<DecisionAuditRecord> mapAll(List<DecisionAuditEntity> entities) {
        return entities.stream().map(mapper::toDomain).toList();
    }
}