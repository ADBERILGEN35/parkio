package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outcome_history")
public class OutcomeHistoryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "evaluation_id", nullable = false, updatable = false)
    private UUID evaluationId;

    @Column(name = "parking_spot_id", nullable = false, updatable = false)
    private UUID parkingSpotId;

    @Column(name = "policy_version", nullable = false, updatable = false, length = 64)
    private String policyVersion;

    @Column(name = "snapshot_schema_version", nullable = false, updatable = false, length = 64)
    private String snapshotSchemaVersion;

    @Column(name = "trigger_type", nullable = false, updatable = false, length = 64)
    private String triggerType;

    @Column(name = "trigger_reference", nullable = false, updatable = false)
    private UUID triggerReference;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    @Column(name = "evidence_cutoff_at", nullable = false, updatable = false)
    private Instant evidenceCutoffAt;

    @Column(name = "classification", nullable = false, updatable = false, length = 64)
    private String classification;

    @Column(name = "confidence", nullable = false, updatable = false)
    private int confidence;

    @Column(name = "primary_reason", nullable = false, updatable = false, length = 64)
    private String primaryReason;

    @Column(name = "validation_window_open", nullable = false, updatable = false)
    private boolean validationWindowOpen;

    @Column(name = "snapshot_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OutcomeHistoryEntity() {}

    public OutcomeHistoryEntity(UUID id, UUID evaluationId, UUID parkingSpotId, String policyVersion,
                                String snapshotSchemaVersion, String triggerType, UUID triggerReference,
                                Instant evaluatedAt, Instant evidenceCutoffAt, String classification,
                                int confidence, String primaryReason, boolean validationWindowOpen,
                                String snapshotJson, Instant createdAt) {
        this.id = id;
        this.evaluationId = evaluationId;
        this.parkingSpotId = parkingSpotId;
        this.policyVersion = policyVersion;
        this.snapshotSchemaVersion = snapshotSchemaVersion;
        this.triggerType = triggerType;
        this.triggerReference = triggerReference;
        this.evaluatedAt = evaluatedAt;
        this.evidenceCutoffAt = evidenceCutoffAt;
        this.classification = classification;
        this.confidence = confidence;
        this.primaryReason = primaryReason;
        this.validationWindowOpen = validationWindowOpen;
        this.snapshotJson = snapshotJson;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getEvaluationId() { return evaluationId; }
    public UUID getParkingSpotId() { return parkingSpotId; }
    public String getPolicyVersion() { return policyVersion; }
    public String getSnapshotSchemaVersion() { return snapshotSchemaVersion; }
    public String getTriggerType() { return triggerType; }
    public UUID getTriggerReference() { return triggerReference; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public Instant getEvidenceCutoffAt() { return evidenceCutoffAt; }
    public String getClassification() { return classification; }
    public int getConfidence() { return confidence; }
    public String getPrimaryReason() { return primaryReason; }
    public boolean isValidationWindowOpen() { return validationWindowOpen; }
    public String getSnapshotJson() { return snapshotJson; }
    public Instant getCreatedAt() { return createdAt; }
}