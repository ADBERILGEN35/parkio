package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pending_reward_ledger")
public class PendingRewardLedgerEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "evaluation_id", nullable = false, updatable = false)
    private UUID evaluationId;

    @Column(name = "reward_subject_type", nullable = false, updatable = false, length = 64)
    private String rewardSubjectType;

    @Column(name = "reward_subject_id", nullable = false, updatable = false)
    private UUID rewardSubjectId;

    @Column(name = "contribution_role", nullable = false, updatable = false, length = 64)
    private String contributionRole;

    @Column(name = "source_outcome_record_id", nullable = false, updatable = false)
    private UUID sourceOutcomeRecordId;

    @Column(name = "source_contribution_id", nullable = false, updatable = false)
    private UUID sourceContributionId;

    @Column(name = "source_parking_spot_id", nullable = false, updatable = false)
    private UUID sourceParkingSpotId;

    @Column(name = "evidence_group_id", nullable = false, updatable = false)
    private UUID evidenceGroupId;

    @Column(name = "reward_policy_version", nullable = false, updatable = false, length = 64)
    private String rewardPolicyVersion;

    @Column(name = "attribution_mapping_version", nullable = false, updatable = false, length = 64)
    private String attributionMappingVersion;

    @Column(name = "snapshot_schema_version", nullable = false, updatable = false, length = 64)
    private String snapshotSchemaVersion;

    @Column(name = "disposition", nullable = false, updatable = false, length = 64)
    private String disposition;

    @Column(name = "reward_unit", nullable = false, updatable = false, length = 32)
    private String rewardUnit;

    @Column(name = "calculated_amount", nullable = false, updatable = false)
    private int calculatedAmount;

    @Column(name = "eligibility", nullable = false, updatable = false, length = 64)
    private String eligibility;

    @Column(name = "primary_reason", nullable = false, updatable = false, length = 64)
    private String primaryReason;

    @Column(name = "outcome_classification", nullable = false, updatable = false, length = 64)
    private String outcomeClassification;

    @Column(name = "outcome_confidence_band", nullable = false, updatable = false, length = 32)
    private String outcomeConfidenceBand;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    @Column(name = "evidence_cutoff_at", nullable = false, updatable = false)
    private Instant evidenceCutoffAt;

    @Column(name = "contribution_json", nullable = false, updatable = false)
    private String contributionJson;

    @Column(name = "evaluation_json", nullable = false, updatable = false)
    private String evaluationJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PendingRewardLedgerEntity() {}

    public PendingRewardLedgerEntity(
            UUID id,
            UUID evaluationId,
            String rewardSubjectType,
            UUID rewardSubjectId,
            String contributionRole,
            UUID sourceOutcomeRecordId,
            UUID sourceContributionId,
            UUID sourceParkingSpotId,
            UUID evidenceGroupId,
            String rewardPolicyVersion,
            String attributionMappingVersion,
            String snapshotSchemaVersion,
            String disposition,
            String rewardUnit,
            int calculatedAmount,
            String eligibility,
            String primaryReason,
            String outcomeClassification,
            String outcomeConfidenceBand,
            Instant evaluatedAt,
            Instant evidenceCutoffAt,
            String contributionJson,
            String evaluationJson,
            Instant createdAt) {
        this.id = id;
        this.evaluationId = evaluationId;
        this.rewardSubjectType = rewardSubjectType;
        this.rewardSubjectId = rewardSubjectId;
        this.contributionRole = contributionRole;
        this.sourceOutcomeRecordId = sourceOutcomeRecordId;
        this.sourceContributionId = sourceContributionId;
        this.sourceParkingSpotId = sourceParkingSpotId;
        this.evidenceGroupId = evidenceGroupId;
        this.rewardPolicyVersion = rewardPolicyVersion;
        this.attributionMappingVersion = attributionMappingVersion;
        this.snapshotSchemaVersion = snapshotSchemaVersion;
        this.disposition = disposition;
        this.rewardUnit = rewardUnit;
        this.calculatedAmount = calculatedAmount;
        this.eligibility = eligibility;
        this.primaryReason = primaryReason;
        this.outcomeClassification = outcomeClassification;
        this.outcomeConfidenceBand = outcomeConfidenceBand;
        this.evaluatedAt = evaluatedAt;
        this.evidenceCutoffAt = evidenceCutoffAt;
        this.contributionJson = contributionJson;
        this.evaluationJson = evaluationJson;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getEvaluationId() { return evaluationId; }
    public String getRewardSubjectType() { return rewardSubjectType; }
    public UUID getRewardSubjectId() { return rewardSubjectId; }
    public String getContributionRole() { return contributionRole; }
    public UUID getSourceOutcomeRecordId() { return sourceOutcomeRecordId; }
    public UUID getSourceContributionId() { return sourceContributionId; }
    public UUID getSourceParkingSpotId() { return sourceParkingSpotId; }
    public UUID getEvidenceGroupId() { return evidenceGroupId; }
    public String getRewardPolicyVersion() { return rewardPolicyVersion; }
    public String getAttributionMappingVersion() { return attributionMappingVersion; }
    public String getSnapshotSchemaVersion() { return snapshotSchemaVersion; }
    public String getDisposition() { return disposition; }
    public String getRewardUnit() { return rewardUnit; }
    public int getCalculatedAmount() { return calculatedAmount; }
    public String getEligibility() { return eligibility; }
    public String getPrimaryReason() { return primaryReason; }
    public String getOutcomeClassification() { return outcomeClassification; }
    public String getOutcomeConfidenceBand() { return outcomeConfidenceBand; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public Instant getEvidenceCutoffAt() { return evidenceCutoffAt; }
    public String getContributionJson() { return contributionJson; }
    public String getEvaluationJson() { return evaluationJson; }
    public Instant getCreatedAt() { return createdAt; }
}
