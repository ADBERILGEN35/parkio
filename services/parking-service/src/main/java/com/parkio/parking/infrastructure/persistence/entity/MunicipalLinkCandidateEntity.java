package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "municipal_link_candidates")
public class MunicipalLinkCandidateEntity {
    @Id private UUID id;
    @Column(name = "facility_a_id") private UUID facilityAId;
    @Column(name = "facility_b_id") private UUID facilityBId;
    @Column(name = "source_key_a", nullable = false) private String sourceKeyA;
    @Column(name = "external_id_a", nullable = false) private String externalIdA;
    @Column(name = "source_key_b", nullable = false) private String sourceKeyB;
    @Column(name = "external_id_b", nullable = false) private String externalIdB;
    @Column(name = "source_family_pair", nullable = false) private String sourceFamilyPair;
    @Column(name = "evidence_signals_json", nullable = false, columnDefinition = "jsonb")
    private String evidenceSignalsJson;
    @Column(name = "score_components_json", nullable = false, columnDefinition = "jsonb")
    private String scoreComponentsJson;
    @Column(name = "total_score", nullable = false) private double totalScore;
    @Column(name = "hard_conflicts", nullable = false, columnDefinition = "jsonb")
    private String hardConflictsJson;
    @Column(name = "generated_at", nullable = false) private Instant generatedAt;
    @Column(name = "source_version_a", nullable = false) private String sourceVersionA;
    @Column(name = "source_version_b", nullable = false) private String sourceVersionB;
    @Column(name = "review_state", nullable = false) private String reviewState;
    @Column(name = "reviewed_by") private String reviewedBy;
    @Column(name = "decision_ts") private Instant decisionTs;
    @Column(name = "rejection_reason") private String rejectionReason;
    @Column(name = "chosen_facility_id") private UUID chosenFacilityId;
    @Column(name = "algorithm_version", nullable = false) private String algorithmVersion;
    @Version private long version;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected MunicipalLinkCandidateEntity() {}

    public MunicipalLinkCandidateEntity(
            UUID id,
            UUID facilityAId,
            UUID facilityBId,
            String sourceKeyA,
            String externalIdA,
            String sourceKeyB,
            String externalIdB,
            String sourceFamilyPair,
            String evidenceSignalsJson,
            String scoreComponentsJson,
            double totalScore,
            String hardConflictsJson,
            Instant generatedAt,
            String sourceVersionA,
            String sourceVersionB,
            String algorithmVersion) {
        this.id = id;
        this.facilityAId = facilityAId;
        this.facilityBId = facilityBId;
        this.sourceKeyA = sourceKeyA;
        this.externalIdA = externalIdA;
        this.sourceKeyB = sourceKeyB;
        this.externalIdB = externalIdB;
        this.sourceFamilyPair = sourceFamilyPair;
        this.evidenceSignalsJson = evidenceSignalsJson;
        this.scoreComponentsJson = scoreComponentsJson;
        this.totalScore = totalScore;
        this.hardConflictsJson = hardConflictsJson;
        this.generatedAt = generatedAt;
        this.sourceVersionA = sourceVersionA;
        this.sourceVersionB = sourceVersionB;
        this.reviewState = "PENDING";
        this.algorithmVersion = algorithmVersion;
        this.createdAt = generatedAt;
        this.updatedAt = generatedAt;
    }

    public UUID getId() { return id; }
    public UUID getFacilityAId() { return facilityAId; }
    public UUID getFacilityBId() { return facilityBId; }
    public String getSourceKeyA() { return sourceKeyA; }
    public String getExternalIdA() { return externalIdA; }
    public String getSourceKeyB() { return sourceKeyB; }
    public String getExternalIdB() { return externalIdB; }
    public String getSourceFamilyPair() { return sourceFamilyPair; }
    public String getEvidenceSignalsJson() { return evidenceSignalsJson; }
    public String getScoreComponentsJson() { return scoreComponentsJson; }
    public double getTotalScore() { return totalScore; }
    public String getHardConflictsJson() { return hardConflictsJson; }
    public Instant getGeneratedAt() { return generatedAt; }
    public String getSourceVersionA() { return sourceVersionA; }
    public String getSourceVersionB() { return sourceVersionB; }
    public String getReviewState() { return reviewState; }
    public String getReviewedBy() { return reviewedBy; }
    public Instant getDecisionTs() { return decisionTs; }
    public String getRejectionReason() { return rejectionReason; }
    public UUID getChosenFacilityId() { return chosenFacilityId; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public long getVersion() { return version; }

    public void decide(String state, String reviewer, String reason, UUID chosenFacilityId, Instant now) {
        this.reviewState = state;
        this.reviewedBy = reviewer;
        this.rejectionReason = reason;
        this.chosenFacilityId = chosenFacilityId;
        this.decisionTs = now;
        this.updatedAt = now;
    }
}
