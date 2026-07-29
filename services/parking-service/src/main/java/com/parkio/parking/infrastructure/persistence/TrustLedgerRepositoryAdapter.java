package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.DuplicateTrustLedgerEntryException;
import com.parkio.parking.application.port.TrustLedgerPort;
import com.parkio.parking.infrastructure.persistence.jpa.TrustLedgerJpaRepository;
import com.parkio.parking.infrastructure.persistence.trust.TrustPersistenceMapper;
import com.parkio.parking.trust.TrustLedgerEntry;
import com.parkio.parking.trust.TrustSubject;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TrustLedgerRepositoryAdapter implements TrustLedgerPort {

    private final TrustLedgerJpaRepository jpa;
    private final TrustPersistenceMapper mapper;
    private final JdbcTemplate jdbc;

    public TrustLedgerRepositoryAdapter(
            TrustLedgerJpaRepository jpa,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc) {
        this.jpa = jpa;
        this.mapper = new TrustPersistenceMapper(objectMapper);
        this.jdbc = jdbc;
    }

    @Override
    public void append(TrustLedgerEntry entry) {
        var entity = mapper.toEntity(entry);
        try {
            jdbc.update(
                    """
                    INSERT INTO trust_ledger (
                        id,
                        evaluation_id,
                        subject_type,
                        subject_id,
                        trust_domain,
                        trust_policy_version,
                        snapshot_schema_version,
                        attribution_mapping_version,
                        source_outcome_record_id,
                        source_evidence_id,
                        source_evidence_group_id,
                        evidence_type,
                        contribution_role,
                        attribution_quality,
                        eligibility,
                        update_direction,
                        trust_level,
                        evaluated_at,
                        created_at,
                        evidence_json,
                        previous_snapshot_json,
                        evaluation_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    entity.getId(),
                    entity.getEvaluationId(),
                    entity.getSubjectType(),
                    entity.getSubjectId(),
                    entity.getTrustDomain(),
                    entity.getTrustPolicyVersion(),
                    entity.getSnapshotSchemaVersion(),
                    entity.getAttributionMappingVersion(),
                    entity.getSourceOutcomeRecordId(),
                    entity.getSourceEvidenceId(),
                    entity.getSourceEvidenceGroupId(),
                    entity.getEvidenceType(),
                    entity.getContributionRole(),
                    entity.getAttributionQuality(),
                    entity.getEligibility(),
                    entity.getUpdateDirection(),
                    entity.getTrustLevel(),
                    Timestamp.from(entity.getEvaluatedAt()),
                    Timestamp.from(entity.getCreatedAt()),
                    entity.getEvidenceJson(),
                    entity.getPreviousSnapshotJson(),
                    entity.getEvaluationJson());
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateTrustLedgerEntryException(
                    "Trust ledger already contains logical update " + entry.evaluationId());
        }
    }

    @Override
    public Optional<TrustLedgerEntry> findByEvaluationId(UUID evaluationId) {
        return jpa.findByEvaluationId(evaluationId).map(mapper::toDomain);
    }

    @Override
    public List<TrustLedgerEntry> findBySubject(TrustSubject subject) {
        return jpa.findBySubjectTypeAndSubjectIdOrderByEvaluatedAtAscIdAsc(subject.type().name(), subject.subjectId())
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}

