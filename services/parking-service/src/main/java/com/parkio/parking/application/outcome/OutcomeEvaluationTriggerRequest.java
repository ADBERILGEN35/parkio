package com.parkio.parking.application.outcome;

import com.parkio.parking.outcome.history.OutcomeEvaluationTrigger;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable internal trigger for one outcome evaluation attempt. */
public record OutcomeEvaluationTriggerRequest(
        UUID evaluationId,
        UUID parkingSpotId,
        OutcomeEvaluationTrigger triggerType,
        UUID triggerReference,
        Instant evidenceCutoffAt,
        Instant createdAt) {

    public OutcomeEvaluationTriggerRequest {
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(triggerType, "triggerType");
        Objects.requireNonNull(triggerReference, "triggerReference");
        Objects.requireNonNull(evidenceCutoffAt, "evidenceCutoffAt");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}