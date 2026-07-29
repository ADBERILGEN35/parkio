package com.parkio.parking.application.outcome;

import com.parkio.parking.outcome.history.OutcomeEvaluationTrigger;
import com.parkio.parking.outcome.policy.OutcomePolicyConfig;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Deterministic idempotency key for operational outcome evaluations. */
public final class OutcomeEvaluationIdentity {

    private OutcomeEvaluationIdentity() {}

    public static UUID forTrigger(
            UUID parkingSpotId,
            OutcomeEvaluationTrigger triggerType,
            UUID triggerReference,
            Instant evidenceCutoffAt) {
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(triggerType, "triggerType");
        Objects.requireNonNull(triggerReference, "triggerReference");
        Objects.requireNonNull(evidenceCutoffAt, "evidenceCutoffAt");
        String material = "outcome-evaluation-v1|"
                + parkingSpotId + '|'
                + OutcomePolicyConfig.POLICY_VERSION.value() + '|'
                + triggerType.name() + '|'
                + triggerReference + '|'
                + evidenceCutoffAt;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }
}