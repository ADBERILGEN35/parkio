package com.parkio.parking.application.port;

import com.parkio.parking.application.exposure.ExposureShadowFailureStage;
import com.parkio.parking.exposure.ExposureComparison;
import com.parkio.parking.exposure.ExposureEvaluation;
import com.parkio.parking.exposure.ExposureReplayComparison;
import java.time.Duration;

public interface ExposureShadowObserverPort {

    ExposureShadowObserverPort NOOP = new ExposureShadowObserverPort() {
    };

    default void recordRequestReceived() {
    }

    default void recordRequestSampled() {
    }

    default void recordRequestSkipped(String reason) {
    }

    default void recordCandidateEvaluated(ExposureEvaluation evaluation) {
    }

    default void recordEvaluationSuccess(ExposureComparison comparison, Duration duration) {
    }

    default void recordEvaluationFailure(ExposureShadowFailureStage stage) {
    }

    default void recordTimeBudgetExceeded() {
    }

    default void recordReplaySuccess(ExposureReplayComparison comparison) {
    }

    default void recordReplayMismatch(ExposureReplayComparison comparison) {
    }

    default void recordReplayFailure() {
    }
}
