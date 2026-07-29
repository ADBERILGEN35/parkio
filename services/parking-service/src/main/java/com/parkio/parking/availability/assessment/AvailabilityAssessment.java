package com.parkio.parking.availability.assessment;

import com.parkio.parking.availability.AvailabilityFreshness;
import com.parkio.parking.availability.AvailabilityReason;
import com.parkio.parking.availability.AvailabilityState;
import com.parkio.parking.availability.score.AvailabilityScore;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Intermediate deterministic assessment before building {@link com.parkio.parking.availability.AvailabilityEvaluation}.
 */
public record AvailabilityAssessment(
        AvailabilityState state,
        AvailabilityScore score,
        AvailabilityFreshness freshness,
        AvailabilityReason primaryReason,
        Set<AvailabilityReason> reasons,
        int remainingTtlBasisPoints,
        int elapsedLifetimeBasisPoints) {

    public AvailabilityAssessment {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(freshness, "freshness");
        Objects.requireNonNull(primaryReason, "primaryReason");
        reasons = reasons == null || reasons.isEmpty()
                ? Set.of(primaryReason)
                : Set.copyOf(new LinkedHashSet<>(reasons));
    }
}
