package com.parkio.parking.availability;

import com.parkio.parking.availability.expiration.AvailabilityExpiration;
import com.parkio.parking.availability.policy.AvailabilityPolicyVersion;
import com.parkio.parking.availability.score.AvailabilityScore;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic output of {@link com.parkio.parking.availability.engine.AvailabilityEngine}.
 */
public record AvailabilityEvaluation(
        UUID parkingSpotId,
        AvailabilityState state,
        AvailabilityScore score,
        AvailabilityFreshness freshness,
        AvailabilityReason primaryReason,
        Set<AvailabilityReason> reasons,
        AvailabilityExpiration expiration,
        AvailabilityPolicyVersion policyVersion,
        Instant evaluatedAt) {

    public AvailabilityEvaluation {
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(freshness, "freshness");
        Objects.requireNonNull(primaryReason, "primaryReason");
        reasons = reasons == null || reasons.isEmpty() ? Set.of(primaryReason) : Set.copyOf(reasons);
        Objects.requireNonNull(expiration, "expiration");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }
}
