package com.parkio.parking.outcome.timeline;

import com.parkio.parking.outcome.signal.OutcomeSignal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Ordered post-publication signal history for one spot. */
public record OutcomeTimeline(
        Instant publishedAt,
        Instant firstSignalAt,
        Instant latestSignalAt,
        Instant validationWindowEnd,
        List<OutcomeSignal> signals) {

    public OutcomeTimeline {
        Objects.requireNonNull(signals, "signals");
        signals = List.copyOf(signals.stream()
                .sorted(Comparator.comparing(OutcomeSignal::occurredAt))
                .toList());
        if (publishedAt != null && firstSignalAt == null && !signals.isEmpty()) {
            firstSignalAt = signals.getFirst().occurredAt();
        }
        if (publishedAt != null && latestSignalAt == null && !signals.isEmpty()) {
            latestSignalAt = signals.getLast().occurredAt();
        }
    }

    public static OutcomeTimeline of(Instant publishedAt, Instant validationWindowEnd, List<OutcomeSignal> signals) {
        Instant first = signals.isEmpty() ? publishedAt : signals.getFirst().occurredAt();
        Instant latest = signals.isEmpty() ? publishedAt : signals.getLast().occurredAt();
        return new OutcomeTimeline(publishedAt, first, latest, validationWindowEnd, signals);
    }

    public Duration validationAgeAt(Instant evaluatedAt) {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (publishedAt == null) {
            return Duration.ZERO;
        }
        return Duration.between(publishedAt, evaluatedAt);
    }

    public boolean hasSignalType(com.parkio.parking.outcome.signal.OutcomeSignalType type) {
        return signals.stream().anyMatch(signal -> signal.type() == type);
    }

    public long countSignalType(com.parkio.parking.outcome.signal.OutcomeSignalType type) {
        return signals.stream().filter(signal -> signal.type() == type).count();
    }
}