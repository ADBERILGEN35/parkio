package com.parkio.parking.availability;

import com.parkio.parking.availability.evaluation.AvailabilityEvaluationContext;
import com.parkio.parking.availability.evidence.AvailabilityEvidence;
import com.parkio.parking.availability.policy.AvailabilityPolicyVersion;
import java.util.Objects;

/**
 * Immutable persisted/replay input for offline availability reconstruction.
 *
 * <p>Separate from {@code decision.audit.DecisionAuditRecord}. Availability history
 * persistence is a future concern; this type defines the replay boundary only.
 */
public record AvailabilitySnapshot(
        AvailabilityEvidence evidence,
        AvailabilityEvaluationContext context,
        AvailabilityEvaluation evaluation) {

    public AvailabilitySnapshot {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(evaluation, "evaluation");
        if (!evidence.parkingSpotId().equals(evaluation.parkingSpotId())) {
            throw new IllegalArgumentException("evidence and evaluation parkingSpotId must match");
        }
        if (!context.evaluatedAt().equals(evaluation.evaluatedAt())) {
            throw new IllegalArgumentException("context and evaluation evaluatedAt must match");
        }
        if (!context.policyVersion().equals(evaluation.policyVersion())) {
            throw new IllegalArgumentException("context and evaluation policyVersion must match");
        }
    }

    public AvailabilityPolicyVersion policyVersion() {
        return context.policyVersion();
    }
}
