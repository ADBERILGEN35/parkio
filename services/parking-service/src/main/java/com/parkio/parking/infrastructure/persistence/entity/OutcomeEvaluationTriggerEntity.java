package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outcome_evaluation_triggers")
public class OutcomeEvaluationTriggerEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "evaluation_id", nullable = false, updatable = false)
    private UUID evaluationId;

    @Column(name = "parking_spot_id", nullable = false, updatable = false)
    private UUID parkingSpotId;

    @Column(name = "trigger_type", nullable = false, updatable = false, length = 64)
    private String triggerType;

    @Column(name = "trigger_reference", nullable = false, updatable = false)
    private UUID triggerReference;

    @Column(name = "evidence_cutoff_at", nullable = false, updatable = false)
    private Instant evidenceCutoffAt;

    @Column(name = "processed", nullable = false)
    private boolean processed;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "last_failure_stage", length = 64)
    private String lastFailureStage;

    @Column(name = "last_failed_at")
    private Instant lastFailedAt;

    @Column(name = "dead_lettered", nullable = false)
    private boolean deadLettered;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OutcomeEvaluationTriggerEntity() {}

    public OutcomeEvaluationTriggerEntity(UUID id, UUID evaluationId, UUID parkingSpotId, String triggerType,
                                          UUID triggerReference, Instant evidenceCutoffAt, boolean processed,
                                          Instant processedAt, int failureCount, String lastFailureStage,
                                          Instant lastFailedAt, boolean deadLettered, Instant createdAt) {
        this.id = id;
        this.evaluationId = evaluationId;
        this.parkingSpotId = parkingSpotId;
        this.triggerType = triggerType;
        this.triggerReference = triggerReference;
        this.evidenceCutoffAt = evidenceCutoffAt;
        this.processed = processed;
        this.processedAt = processedAt;
        this.failureCount = failureCount;
        this.lastFailureStage = lastFailureStage;
        this.lastFailedAt = lastFailedAt;
        this.deadLettered = deadLettered;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getEvaluationId() { return evaluationId; }
    public UUID getParkingSpotId() { return parkingSpotId; }
    public String getTriggerType() { return triggerType; }
    public UUID getTriggerReference() { return triggerReference; }
    public Instant getEvidenceCutoffAt() { return evidenceCutoffAt; }
    public boolean isProcessed() { return processed; }
    public Instant getProcessedAt() { return processedAt; }
    public int getFailureCount() { return failureCount; }
    public String getLastFailureStage() { return lastFailureStage; }
    public Instant getLastFailedAt() { return lastFailedAt; }
    public boolean isDeadLettered() { return deadLettered; }
    public Instant getCreatedAt() { return createdAt; }

    public void markProcessed(Instant processedAt) {
        this.processed = true;
        this.processedAt = processedAt;
    }

    public void recordFailure(String failureStage, Instant failedAt, int maxAttempts) {
        this.failureCount += 1;
        this.lastFailureStage = failureStage;
        this.lastFailedAt = failedAt;
        if (this.failureCount >= maxAttempts) {
            this.deadLettered = true;
        }
    }
}