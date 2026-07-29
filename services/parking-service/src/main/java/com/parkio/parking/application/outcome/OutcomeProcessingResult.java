package com.parkio.parking.application.outcome;

import com.parkio.parking.outcome.history.OutcomeEvaluationTrigger;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record OutcomeProcessingResult(
        UUID evaluationId,
        OutcomeEvaluationTrigger triggerType,
        Status status,
        Optional<OutcomeProcessingFailureStage> failureStage) {

    public enum Status {
        APPENDED,
        DUPLICATE,
        INELIGIBLE,
        FAILED
    }

    public OutcomeProcessingResult {
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(triggerType, "triggerType");
        Objects.requireNonNull(status, "status");
        failureStage = failureStage == null ? Optional.empty() : failureStage;
    }

    public static OutcomeProcessingResult appended(UUID evaluationId, OutcomeEvaluationTrigger triggerType) {
        return new OutcomeProcessingResult(evaluationId, triggerType, Status.APPENDED, Optional.empty());
    }

    public static OutcomeProcessingResult duplicate(UUID evaluationId, OutcomeEvaluationTrigger triggerType) {
        return new OutcomeProcessingResult(
                evaluationId,
                triggerType,
                Status.DUPLICATE,
                Optional.of(OutcomeProcessingFailureStage.DUPLICATE_ALREADY_RECORDED));
    }

    public static OutcomeProcessingResult ineligible(UUID evaluationId, OutcomeEvaluationTrigger triggerType) {
        return new OutcomeProcessingResult(
                evaluationId,
                triggerType,
                Status.INELIGIBLE,
                Optional.of(OutcomeProcessingFailureStage.TRIGGER_INELIGIBLE));
    }

    public static OutcomeProcessingResult failed(
            UUID evaluationId,
            OutcomeEvaluationTrigger triggerType,
            OutcomeProcessingFailureStage stage) {
        return new OutcomeProcessingResult(evaluationId, triggerType, Status.FAILED, Optional.of(stage));
    }
}