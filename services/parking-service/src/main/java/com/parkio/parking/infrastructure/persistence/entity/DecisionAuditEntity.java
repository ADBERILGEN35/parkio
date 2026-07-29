package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for append-only {@code decision_audit}. All columns are non-updatable. */
@Entity
@Table(name = "decision_audit")
public class DecisionAuditEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "parking_spot_id", nullable = false, updatable = false)
    private UUID parkingSpotId;

    @Column(name = "evaluation_id", nullable = false, updatable = false)
    private UUID evaluationId;

    @Column(name = "policy_version", nullable = false, updatable = false, length = 64)
    private String policyVersion;

    @Column(name = "decision_engine_version", nullable = false, updatable = false, length = 64)
    private String decisionEngineVersion;

    @Column(name = "shadow_mode_version", nullable = false, updatable = false, length = 64)
    private String shadowModeVersion;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    @Column(name = "disposition", nullable = false, updatable = false, length = 32)
    private String disposition;

    @Column(name = "comparison_category", nullable = false, updatable = false, length = 64)
    private String comparisonCategory;

    @Column(name = "decisive_rule", nullable = false, updatable = false, length = 64)
    private String decisiveRule;

    @Column(name = "risk_band", nullable = false, updatable = false, length = 32)
    private String riskBand;

    @Column(name = "evidence_profile", nullable = false, updatable = false, length = 64)
    private String evidenceProfile;

    @Column(name = "hard_constraint_family", nullable = false, updatable = false, length = 32)
    private String hardConstraintFamily;

    @Column(name = "snapshot_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "execution_mode", nullable = false, updatable = false, length = 32)
    private String executionMode = "SHADOW";

    @Column(name = "authority_algorithm_version", updatable = false, length = 64)
    private String authorityAlgorithmVersion;

    @Column(name = "canary_bucket", updatable = false)
    private Integer canaryBucket;

    @Column(name = "authority_applied", nullable = false, updatable = false)
    private boolean authorityApplied;

    @Column(name = "applied_status", updatable = false, length = 32)
    private String appliedStatus;

    protected DecisionAuditEntity() {}

    public DecisionAuditEntity(
            UUID id,
            UUID parkingSpotId,
            UUID evaluationId,
            String policyVersion,
            String decisionEngineVersion,
            String shadowModeVersion,
            Instant evaluatedAt,
            String disposition,
            String comparisonCategory,
            String decisiveRule,
            String riskBand,
            String evidenceProfile,
            String hardConstraintFamily,
            String snapshotJson,
            Instant createdAt,
            String executionMode,
            String authorityAlgorithmVersion,
            Integer canaryBucket,
            boolean authorityApplied,
            String appliedStatus) {
        this.id = id;
        this.parkingSpotId = parkingSpotId;
        this.evaluationId = evaluationId;
        this.policyVersion = policyVersion;
        this.decisionEngineVersion = decisionEngineVersion;
        this.shadowModeVersion = shadowModeVersion;
        this.evaluatedAt = evaluatedAt;
        this.disposition = disposition;
        this.comparisonCategory = comparisonCategory;
        this.decisiveRule = decisiveRule;
        this.riskBand = riskBand;
        this.evidenceProfile = evidenceProfile;
        this.hardConstraintFamily = hardConstraintFamily;
        this.snapshotJson = snapshotJson;
        this.createdAt = createdAt;
        this.executionMode = executionMode;
        this.authorityAlgorithmVersion = authorityAlgorithmVersion;
        this.canaryBucket = canaryBucket;
        this.authorityApplied = authorityApplied;
        this.appliedStatus = appliedStatus;
    }

    public UUID getId() { return id; }
    public UUID getParkingSpotId() { return parkingSpotId; }
    public UUID getEvaluationId() { return evaluationId; }
    public String getPolicyVersion() { return policyVersion; }
    public String getDecisionEngineVersion() { return decisionEngineVersion; }
    public String getShadowModeVersion() { return shadowModeVersion; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public String getDisposition() { return disposition; }
    public String getComparisonCategory() { return comparisonCategory; }
    public String getDecisiveRule() { return decisiveRule; }
    public String getRiskBand() { return riskBand; }
    public String getEvidenceProfile() { return evidenceProfile; }
    public String getHardConstraintFamily() { return hardConstraintFamily; }
    public String getSnapshotJson() { return snapshotJson; }
    public Instant getCreatedAt() { return createdAt; }
    public String getExecutionMode() { return executionMode; }
    public String getAuthorityAlgorithmVersion() { return authorityAlgorithmVersion; }
    public Integer getCanaryBucket() { return canaryBucket; }
    public boolean isAuthorityApplied() { return authorityApplied; }
    public String getAppliedStatus() { return appliedStatus; }
}