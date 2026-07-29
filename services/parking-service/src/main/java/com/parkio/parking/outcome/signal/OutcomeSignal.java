package com.parkio.parking.outcome.signal;

import java.time.Instant;
import java.util.Objects;

/** Immutable timed observation used in an outcome timeline. */
public record OutcomeSignal(
        OutcomeSignalType type,
        OutcomeSignalSource source,
        Instant occurredAt) {

    public OutcomeSignal {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}