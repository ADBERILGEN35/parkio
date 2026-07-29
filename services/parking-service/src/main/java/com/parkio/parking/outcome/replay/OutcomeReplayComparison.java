package com.parkio.parking.outcome.replay;

import com.parkio.parking.outcome.OutcomeEvaluation;
import java.util.Objects;

public record OutcomeReplayComparison(
        OutcomeEvaluation original,
        OutcomeEvaluation replayed,
        boolean matches) {

    public OutcomeReplayComparison {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(replayed, "replayed");
    }

    public static OutcomeReplayComparison of(OutcomeEvaluation original, OutcomeEvaluation replayed) {
        boolean matches = original.classification() == replayed.classification()
                && original.confidence().value() == replayed.confidence().value()
                && original.primaryReason() == replayed.primaryReason()
                && original.reasons().equals(replayed.reasons())
                && original.validationWindowOpen() == replayed.validationWindowOpen();
        return new OutcomeReplayComparison(original, replayed, matches);
    }
}