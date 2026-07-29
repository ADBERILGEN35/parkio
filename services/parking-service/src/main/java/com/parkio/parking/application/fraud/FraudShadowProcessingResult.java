package com.parkio.parking.application.fraud;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Result of processing one durable outcome through fraud shadow. */
public record FraudShadowProcessingResult(
        UUID sourceOutcomeRecordId,
        Status status,
        Optional<FraudShadowFailureStage> failureStage) {

    public FraudShadowProcessingResult {
        Objects.requireNonNull(sourceOutcomeRecordId, "sourceOutcomeRecordId");
        Objects.requireNonNull(status, "status");
        failureStage = failureStage == null ? Optional.empty() : failureStage;
    }

    public static FraudShadowProcessingResult appended(UUID sourceOutcomeRecordId) {
        return new FraudShadowProcessingResult(sourceOutcomeRecordId, Status.APPENDED, Optional.empty());
    }

    public static FraudShadowProcessingResult duplicate(UUID sourceOutcomeRecordId) {
        return new FraudShadowProcessingResult(sourceOutcomeRecordId, Status.DUPLICATE, Optional.empty());
    }

    public static FraudShadowProcessingResult skipped(UUID sourceOutcomeRecordId) {
        return new FraudShadowProcessingResult(sourceOutcomeRecordId, Status.SKIPPED, Optional.empty());
    }

    public static FraudShadowProcessingResult failed(UUID sourceOutcomeRecordId, FraudShadowFailureStage stage) {
        return new FraudShadowProcessingResult(sourceOutcomeRecordId, Status.FAILED, Optional.of(stage));
    }

    public enum Status {
        APPENDED,
        DUPLICATE,
        SKIPPED,
        FAILED
    }
}
