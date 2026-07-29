package com.parkio.parking.outcome.history;

import com.parkio.parking.outcome.history.OutcomeEvaluationTrigger;
import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeReason;
import com.parkio.parking.outcome.OutcomeSnapshot;
import com.parkio.parking.outcome.confidence.OutcomeConfidence;
import com.parkio.parking.outcome.policy.OutcomePolicyVersion;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable durable record for one completed outcome evaluation. */
public record OutcomeHistoryRecord(
        UUID recordId,
        UUID evaluationId,
        UUID parkingSpotId,
        OutcomePolicyVersion policyVersion,
        String snapshotSchemaVersion,
        OutcomeEvaluationTrigger triggerType,
        UUID triggerReference,
        Instant evaluatedAt,
        Instant evidenceCutoffAt,
        OutcomeSnapshot snapshot,
        OutcomeClassification classification,
        OutcomeConfidence confidence,
        OutcomeReason primaryReason,
        boolean validationWindowOpen,
        Instant createdAt) {

    public OutcomeHistoryRecord {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(snapshotSchemaVersion, "snapshotSchemaVersion");
        Objects.requireNonNull(triggerType, "triggerType");
        Objects.requireNonNull(triggerReference, "triggerReference");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(evidenceCutoffAt, "evidenceCutoffAt");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(primaryReason, "primaryReason");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!snapshot.evidence().parkingSpotId().equals(parkingSpotId)) {
            throw new IllegalArgumentException("snapshot parkingSpotId must match record parkingSpotId");
        }
        if (!snapshot.evaluation().policyVersion().equals(policyVersion)) {
            throw new IllegalArgumentException("snapshot policyVersion must match record policyVersion");
        }
    }
}