package com.parkio.parking.application.reward;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record RewardShadowProcessingResult(
        UUID sourceOutcomeRecordId,
        Status status,
        Optional<RewardShadowFailureStage> failureStage) {

    public enum Status {
        APPENDED,
        DUPLICATE,
        FAILED
    }

    public RewardShadowProcessingResult {
        Objects.requireNonNull(sourceOutcomeRecordId, "sourceOutcomeRecordId");
        Objects.requireNonNull(status, "status");
        failureStage = failureStage == null ? Optional.empty() : failureStage;
    }

    public static RewardShadowProcessingResult appended(UUID sourceOutcomeRecordId) {
        return new RewardShadowProcessingResult(sourceOutcomeRecordId, Status.APPENDED, Optional.empty());
    }

    public static RewardShadowProcessingResult duplicate(UUID sourceOutcomeRecordId) {
        return new RewardShadowProcessingResult(
                sourceOutcomeRecordId,
                Status.DUPLICATE,
                Optional.of(RewardShadowFailureStage.LEDGER_APPEND_FAILURE));
    }

    public static RewardShadowProcessingResult failed(
            UUID sourceOutcomeRecordId,
            RewardShadowFailureStage stage) {
        return new RewardShadowProcessingResult(sourceOutcomeRecordId, Status.FAILED, Optional.of(stage));
    }
}
