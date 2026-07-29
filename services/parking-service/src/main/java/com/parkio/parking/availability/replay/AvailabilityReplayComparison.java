package com.parkio.parking.availability.replay;

import com.parkio.parking.availability.AvailabilityEvaluation;
import com.parkio.parking.availability.AvailabilityState;
import java.util.Objects;

/**
 * Result of replaying a captured availability snapshot.
 */
public record AvailabilityReplayComparison(
        AvailabilityEvaluation original,
        AvailabilityEvaluation replayed,
        boolean matches) {

    public AvailabilityReplayComparison {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(replayed, "replayed");
    }

    public static AvailabilityReplayComparison of(AvailabilityEvaluation original, AvailabilityEvaluation replayed) {
        boolean matches = original.state() == replayed.state()
                && original.score().value() == replayed.score().value()
                && original.freshness() == replayed.freshness()
                && original.primaryReason() == replayed.primaryReason()
                && original.reasons().equals(replayed.reasons())
                && original.expiration().expired() == replayed.expiration().expired();
        return new AvailabilityReplayComparison(original, replayed, matches);
    }

    public AvailabilityState stateDelta() {
        return replayed.state();
    }
}
