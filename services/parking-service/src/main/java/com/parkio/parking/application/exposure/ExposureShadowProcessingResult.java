package com.parkio.parking.application.exposure;

import java.util.Objects;
import java.util.Optional;

public record ExposureShadowProcessingResult(
        Status status,
        Optional<ExposureShadowFailureStage> failureStage) {

    public enum Status {
        SUCCESS,
        SKIPPED,
        FAILED
    }

    public ExposureShadowProcessingResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(failureStage, "failureStage");
    }

    public static ExposureShadowProcessingResult success() {
        return new ExposureShadowProcessingResult(Status.SUCCESS, Optional.empty());
    }

    public static ExposureShadowProcessingResult skipped() {
        return new ExposureShadowProcessingResult(Status.SKIPPED, Optional.empty());
    }

    public static ExposureShadowProcessingResult failed(ExposureShadowFailureStage stage) {
        return new ExposureShadowProcessingResult(Status.FAILED, Optional.of(stage));
    }
}
