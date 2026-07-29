package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.DuplicateFraudLedgerEntryException;
import com.parkio.parking.application.port.FraudLedgerPort;
import com.parkio.parking.fraud.FraudLedgerEntry;
import com.parkio.parking.fraud.FraudSubject;
import com.parkio.parking.infrastructure.persistence.fraud.FraudPersistenceMapper;
import com.parkio.parking.infrastructure.persistence.jpa.FraudLedgerJpaRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FraudLedgerRepositoryAdapter implements FraudLedgerPort {

    private final FraudLedgerJpaRepository jpa;
    private final FraudPersistenceMapper mapper;
    private final JdbcTemplate jdbc;

    public FraudLedgerRepositoryAdapter(
            FraudLedgerJpaRepository jpa,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc) {
        this.jpa = jpa;
        this.mapper = new FraudPersistenceMapper(objectMapper);
        this.jdbc = jdbc;
    }

    @Override
    public void append(FraudLedgerEntry entry) {
        var entity = mapper.toEntity(entry);
        try {
            jdbc.update(
                    """
                    INSERT INTO fraud_evaluation_ledger (
                        id,
                        evaluation_id,
                        subject_type,
                        subject_id,
                        fraud_domain,
                        policy_version,
                        schema_version,
                        mapping_version,
                        aggregation_version,
                        source_outcome_record_id,
                        evidence_window_start,
                        evidence_window_end,
                        risk_score,
                        risk_band,
                        confidence_band,
                        effective_evidence_count,
                        disposition,
                        decisive_rule,
                        evaluated_at,
                        created_at,
                        evaluation_snapshot_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    entity.getId(),
                    entity.getEvaluationId(),
                    entity.getSubjectType(),
                    entity.getSubjectId(),
                    entity.getFraudDomain(),
                    entity.getPolicyVersion(),
                    entity.getSchemaVersion(),
                    entity.getMappingVersion(),
                    entity.getAggregationVersion(),
                    entity.getSourceOutcomeRecordId(),
                    Timestamp.from(entity.getEvidenceWindowStart()),
                    Timestamp.from(entity.getEvidenceWindowEnd()),
                    entity.getRiskScore(),
                    entity.getRiskBand(),
                    entity.getConfidenceBand(),
                    entity.getEffectiveEvidenceCount(),
                    entity.getDisposition(),
                    entity.getDecisiveRule(),
                    Timestamp.from(entity.getEvaluatedAt()),
                    Timestamp.from(entity.getCreatedAt()),
                    entity.getEvaluationSnapshotJson());
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateFraudLedgerEntryException(
                    "Fraud ledger already contains logical evaluation " + entry.evaluationId());
        }
    }

    @Override
    public Optional<FraudLedgerEntry> findByEvaluationId(UUID evaluationId) {
        return jpa.findByEvaluationId(evaluationId).map(mapper::toDomain);
    }

    @Override
    public List<FraudLedgerEntry> findBySubject(FraudSubject subject) {
        return jpa.findBySubjectTypeAndSubjectIdOrderByEvaluatedAtAscIdAsc(subject.type().name(), subject.subjectId())
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
