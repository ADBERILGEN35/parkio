package com.parkio.parking.availability.evaluation;

import com.parkio.parking.availability.policy.AvailabilityPolicyVersion;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Clock-injected evaluation context for deterministic availability replay.
 */
public record AvailabilityEvaluationContext(
        Instant evaluatedAt,
        AvailabilityPolicyVersion policyVersion,
        Duration advertisedLifetime) {

    public AvailabilityEvaluationContext {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(advertisedLifetime, "advertisedLifetime");
        if (advertisedLifetime.isZero() || advertisedLifetime.isNegative()) {
            throw new IllegalArgumentException("advertisedLifetime must be positive");
        }
    }
}
