package com.parkio.parking.application.port;

import com.parkio.parking.availability.AvailabilityEvaluation;
import com.parkio.parking.availability.AvailabilityFreshness;
import com.parkio.parking.availability.AvailabilityState;
import java.time.Duration;

/**
 * Low-cardinality availability observability.
 *
 * <p>Implementations must not tag parking spot IDs or exact scores.
 */
public interface AvailabilityObserverPort {

    void recordEvaluation(AvailabilityEvaluation evaluation, Duration duration);

    /** No-op observer when availability metrics are unavailable. */
    static AvailabilityObserverPort noop() {
        return (evaluation, duration) -> {};
    }

    /** Bounded tag helpers for adapters. */
    static String stateTag(AvailabilityState state) {
        return state.name();
    }

    static String freshnessTag(AvailabilityFreshness freshness) {
        return freshness.name();
    }
}