package com.parkio.parking.application.trust;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Result of processing one durable outcome through trust shadow. */
public record TrustShadowProcessingResult(
        UUID sourceOutcomeRecordId,
        Status status,
        Optional<TrustShadowFailureStage> failureStage) {

    public TrustShadowProcessingResult {
        Objects.requireNonNull(sourceOutcomeRecordId, "sourceOutcomeRecordId");
        Objects.requireNonNull(status, "status");
        failureStage = failureStage == null ? Optional.empty() : failureStage;
    }

    public static TrustShadowProcessingResult appended(UUID sourceOutcomeRecordId) {
        return new TrustShadowProcessingResult(sourceOutcomeRecordId, Status.APPENDED, Optional.empty());
    }

    public static TrustShadowProcessingResult duplicate(UUID sourceOutcomeRecordId) {
        return new TrustShadowProcessingResult(sourceOutcomeRecordId, Status.DUPLICATE, Optional.empty());
    }

    public static TrustShadowProcessingResult skipped(UUID sourceOutcomeRecordId) {
        return new TrustShadowProcessingResult(sourceOutcomeRecordId, Status.SKIPPED, Optional.empty());
    }

    public static TrustShadowProcessingResult failed(UUID sourceOutcomeRecordId, TrustShadowFailureStage stage) {
        return new TrustShadowProcessingResult(sourceOutcomeRecordId, Status.FAILED, Optional.of(stage));
    }

    public enum Status {
        APPENDED,
        DUPLICATE,
        SKIPPED,
        FAILED
    }
}

