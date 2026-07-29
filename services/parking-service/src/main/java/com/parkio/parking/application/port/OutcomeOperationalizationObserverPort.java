package com.parkio.parking.application.port;

import com.parkio.parking.application.outcome.OutcomeConfidenceBand;
import com.parkio.parking.outcome.history.OutcomeEvaluationTrigger;
import com.parkio.parking.application.outcome.OutcomeProcessingFailureStage;
import com.parkio.parking.application.outcome.OutcomeProcessingResult;
import com.parkio.parking.outcome.OutcomeEvaluation;
import java.time.Duration;

public interface OutcomeOperationalizationObserverPort extends OutcomeObserverPort {

    default void recordTriggerReceived(OutcomeEvaluationTrigger triggerType) {}

    default void recordTriggerOutcome(OutcomeEvaluationTrigger triggerType, OutcomeProcessingResult result) {}

    default void recordEvaluationSuccess(
            OutcomeEvaluation evaluation,
            OutcomeEvaluationTrigger triggerType,
            OutcomeConfidenceBand confidenceBand,
            Duration duration) {}

    default void recordEvaluationFailure(OutcomeProcessingFailureStage stage, OutcomeEvaluationTrigger triggerType) {}

    default void recordHistoryAppendSuccess() {}

    default void recordHistoryAppendDuplicate() {}

    default void recordHistoryAppendFailure() {}

    default void recordSchedulerCandidates(int count) {}

    default void recordSchedulerCompleted(int count) {}

    default void recordSchedulerFailed() {}

    default void recordReplaySuccess() {}

    default void recordReplayMismatch() {}

    default void recordReplayFailure() {}

    @Override
    default void recordEvaluation(OutcomeEvaluation evaluation, Duration duration) {}

    static OutcomeOperationalizationObserverPort noop() {
        return new OutcomeOperationalizationObserverPort() {};
    }
}