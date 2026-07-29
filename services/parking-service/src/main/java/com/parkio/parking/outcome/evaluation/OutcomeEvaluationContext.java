package com.parkio.parking.outcome.evaluation;

import com.parkio.parking.outcome.policy.OutcomePolicyVersion;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Clock-injected outcome validation context. */
public record OutcomeEvaluationContext(
        Instant evaluatedAt,
        OutcomePolicyVersion policyVersion,
        Duration validationWindow) {

    public OutcomeEvaluationContext {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(validationWindow, "validationWindow");
        if (validationWindow.isZero() || validationWindow.isNegative()) {
            throw new IllegalArgumentException("validationWindow must be positive");
        }
    }
}