package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.DuplicatePendingRewardIntentException;
import com.parkio.parking.application.port.RewardLedgerPort;
import com.parkio.parking.infrastructure.persistence.jpa.PendingRewardLedgerJpaRepository;
import com.parkio.parking.infrastructure.persistence.reward.RewardPersistenceMapper;
import com.parkio.parking.reward.PendingRewardIntent;
import com.parkio.parking.reward.RewardSubject;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PendingRewardLedgerRepositoryAdapter implements RewardLedgerPort {

    private final PendingRewardLedgerJpaRepository jpa;
    private final RewardPersistenceMapper mapper;
    private final JdbcTemplate jdbc;

    public PendingRewardLedgerRepositoryAdapter(
            PendingRewardLedgerJpaRepository jpa,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc) {
        this.jpa = jpa;
        this.mapper = new RewardPersistenceMapper(objectMapper);
        this.jdbc = jdbc;
    }

    @Override
    public void append(PendingRewardIntent intent) {
        var entity = mapper.toEntity(intent);
        try {
            jdbc.update(
                    """
                    INSERT INTO pending_reward_ledger (
                        id,
                        evaluation_id,
                        reward_subject_type,
                        reward_subject_id,
                        contribution_role,
                        source_outcome_record_id,
                        source_contribution_id,
                        source_parking_spot_id,
                        evidence_group_id,
                        reward_policy_version,
                        attribution_mapping_version,
                        snapshot_schema_version,
                        disposition,
                        reward_unit,
                        calculated_amount,
                        eligibility,
                        primary_reason,
                        outcome_classification,
                        outcome_confidence_band,
                        evaluated_at,
                        evidence_cutoff_at,
                        contribution_json,
                        evaluation_json,
                        created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    entity.getId(),
                    entity.getEvaluationId(),
                    entity.getRewardSubjectType(),
                    entity.getRewardSubjectId(),
                    entity.getContributionRole(),
                    entity.getSourceOutcomeRecordId(),
                    entity.getSourceContributionId(),
                    entity.getSourceParkingSpotId(),
                    entity.getEvidenceGroupId(),
                    entity.getRewardPolicyVersion(),
                    entity.getAttributionMappingVersion(),
                    entity.getSnapshotSchemaVersion(),
                    entity.getDisposition(),
                    entity.getRewardUnit(),
                    entity.getCalculatedAmount(),
                    entity.getEligibility(),
                    entity.getPrimaryReason(),
                    entity.getOutcomeClassification(),
                    entity.getOutcomeConfidenceBand(),
                    Timestamp.from(entity.getEvaluatedAt()),
                    Timestamp.from(entity.getEvidenceCutoffAt()),
                    entity.getContributionJson(),
                    entity.getEvaluationJson(),
                    Timestamp.from(entity.getCreatedAt()));
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicatePendingRewardIntentException(
                    "Pending reward ledger already contains logical intent " + intent.evaluationId());
        }
    }

    @Override
    public Optional<PendingRewardIntent> findByEvaluationId(UUID evaluationId) {
        return jpa.findByEvaluationId(evaluationId).map(mapper::toDomain);
    }

    @Override
    public List<PendingRewardIntent> findBySubject(RewardSubject subject) {
        return jpa.findByRewardSubjectTypeAndRewardSubjectIdOrderByEvaluatedAtAscIdAsc(
                        subject.type().name(),
                        subject.subjectId())
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public Optional<PendingRewardIntent> findLatestForContribution(UUID sourceContributionId) {
        return jpa.findTopBySourceContributionIdOrderByEvaluatedAtDescIdDesc(sourceContributionId)
                .map(mapper::toDomain);
    }
}
