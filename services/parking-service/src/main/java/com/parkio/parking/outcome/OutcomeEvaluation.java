package com.parkio.parking.outcome;

import com.parkio.parking.outcome.confidence.OutcomeConfidence;
import com.parkio.parking.outcome.policy.OutcomePolicyVersion;
import com.parkio.parking.outcome.timeline.OutcomeTimeline;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Deterministic output of {@link com.parkio.parking.outcome.engine.OutcomeValidationEngine}. */
public record OutcomeEvaluation(
        UUID parkingSpotId,
        OutcomeClassification classification,
        OutcomeConfidence confidence,
        OutcomeReason primaryReason,
        Set<OutcomeReason> reasons,
        OutcomeTimeline timeline,
        Duration validationAge,
        boolean validationWindowOpen,
        OutcomePolicyVersion policyVersion,
        Instant evaluatedAt) {

    public OutcomeEvaluation {
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(primaryReason, "primaryReason");
        reasons = reasons == null || reasons.isEmpty() ? Set.of(primaryReason) : Set.copyOf(reasons);
        Objects.requireNonNull(timeline, "timeline");
        Objects.requireNonNull(validationAge, "validationAge");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }
}