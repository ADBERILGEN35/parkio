package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trust_ledger")
public class TrustLedgerEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "evaluation_id", nullable = false, updatable = false)
    private UUID evaluationId;

    @Column(name = "subject_type", nullable = false, updatable = false, length = 64)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "trust_domain", nullable = false, updatable = false, length = 64)
    private String trustDomain;

    @Column(name = "trust_policy_version", nullable = false, updatable = false, length = 64)
    private String trustPolicyVersion;

    @Column(name = "snapshot_schema_version", nullable = false, updatable = false, length = 64)
    private String snapshotSchemaVersion;

    @Column(name = "attribution_mapping_version", nullable = false, updatable = false, length = 64)
    private String attributionMappingVersion;

    @Column(name = "source_outcome_record_id", nullable = false, updatable = false)
    private UUID sourceOutcomeRecordId;

    @Column(name = "source_evidence_id", nullable = false, updatable = false)
    private UUID sourceEvidenceId;

    @Column(name = "source_evidence_group_id", nullable = false, updatable = false)
    private UUID sourceEvidenceGroupId;

    @Column(name = "evidence_type", nullable = false, updatable = false, length = 64)
    private String evidenceType;

    @Column(name = "contribution_role", nullable = false, updatable = false, length = 64)
    private String contributionRole;

    @Column(name = "attribution_quality", nullable = false, updatable = false, length = 64)
    private String attributionQuality;

    @Column(name = "eligibility", nullable = false, updatable = false, length = 64)
    private String eligibility;

    @Column(name = "update_direction", nullable = false, updatable = false, length = 64)
    private String updateDirection;

    @Column(name = "trust_level", nullable = false, updatable = false, length = 64)
    private String trustLevel;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "evidence_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String evidenceJson;

    @Column(name = "previous_snapshot_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String previousSnapshotJson;

    @Column(name = "evaluation_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String evaluationJson;

    protected TrustLedgerEntity() {}

    public TrustLedgerEntity(
            UUID id,
            UUID evaluationId,
            String subjectType,
            UUID subjectId,
            String trustDomain,
            String trustPolicyVersion,
            String snapshotSchemaVersion,
            String attributionMappingVersion,
            UUID sourceOutcomeRecordId,
            UUID sourceEvidenceId,
            UUID sourceEvidenceGroupId,
            String evidenceType,
            String contributionRole,
            String attributionQuality,
            String eligibility,
            String updateDirection,
            String trustLevel,
            Instant evaluatedAt,
            Instant createdAt,
            String evidenceJson,
            String previousSnapshotJson,
            String evaluationJson) {
        this.id = id;
        this.evaluationId = evaluationId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.trustDomain = trustDomain;
        this.trustPolicyVersion = trustPolicyVersion;
        this.snapshotSchemaVersion = snapshotSchemaVersion;
        this.attributionMappingVersion = attributionMappingVersion;
        this.sourceOutcomeRecordId = sourceOutcomeRecordId;
        this.sourceEvidenceId = sourceEvidenceId;
        this.sourceEvidenceGroupId = sourceEvidenceGroupId;
        this.evidenceType = evidenceType;
        this.contributionRole = contributionRole;
        this.attributionQuality = attributionQuality;
        this.eligibility = eligibility;
        this.updateDirection = updateDirection;
        this.trustLevel = trustLevel;
        this.evaluatedAt = evaluatedAt;
        this.createdAt = createdAt;
        this.evidenceJson = evidenceJson;
        this.previousSnapshotJson = previousSnapshotJson;
        this.evaluationJson = evaluationJson;
    }

    public UUID getId() { return id; }
    public UUID getEvaluationId() { return evaluationId; }
    public String getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public String getTrustDomain() { return trustDomain; }
    public String getTrustPolicyVersion() { return trustPolicyVersion; }
    public String getSnapshotSchemaVersion() { return snapshotSchemaVersion; }
    public String getAttributionMappingVersion() { return attributionMappingVersion; }
    public UUID getSourceOutcomeRecordId() { return sourceOutcomeRecordId; }
    public UUID getSourceEvidenceId() { return sourceEvidenceId; }
    public UUID getSourceEvidenceGroupId() { return sourceEvidenceGroupId; }
    public String getEvidenceType() { return evidenceType; }
    public String getContributionRole() { return contributionRole; }
    public String getAttributionQuality() { return attributionQuality; }
    public String getEligibility() { return eligibility; }
    public String getUpdateDirection() { return updateDirection; }
    public String getTrustLevel() { return trustLevel; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getEvidenceJson() { return evidenceJson; }
    public String getPreviousSnapshotJson() { return previousSnapshotJson; }
    public String getEvaluationJson() { return evaluationJson; }
}

