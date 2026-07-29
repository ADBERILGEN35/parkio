package com.parkio.parking.application.port;

import com.parkio.parking.application.outcome.OutcomeEvaluationTriggerRequest;
import java.time.Instant;
import java.util.List;

public interface OutcomeEvaluationTriggerPort {

    void enqueue(OutcomeEvaluationTriggerRequest trigger);

    List<OutcomeEvaluationTriggerRequest> claimPendingBatch(int limit);

    void markProcessed(OutcomeEvaluationTriggerRequest trigger, Instant processedAt);

    void recordFailure(OutcomeEvaluationTriggerRequest trigger, String failureStage, Instant failedAt);

    static OutcomeEvaluationTriggerPort noop() {
        return new OutcomeEvaluationTriggerPort() {
            @Override
            public void enqueue(OutcomeEvaluationTriggerRequest trigger) {}

            @Override
            public List<OutcomeEvaluationTriggerRequest> claimPendingBatch(int limit) {
                return List.of();
            }

            @Override
            public void markProcessed(OutcomeEvaluationTriggerRequest trigger, Instant processedAt) {}

            @Override
            public void recordFailure(OutcomeEvaluationTriggerRequest trigger, String failureStage, Instant failedAt) {}
        };
    }
}