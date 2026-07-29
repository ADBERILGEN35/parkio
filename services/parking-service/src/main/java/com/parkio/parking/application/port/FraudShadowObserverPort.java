package com.parkio.parking.application.port;

import com.parkio.parking.application.fraud.FraudShadowFailureStage;
import com.parkio.parking.application.fraud.FraudShadowProcessingResult;
import com.parkio.parking.fraud.FraudEvaluation;
import com.parkio.parking.fraud.FraudFeatureVector;
import com.parkio.parking.fraud.FraudReplayComparison;
import java.time.Duration;

/** Observability boundary for fraud shadow processing. */
public interface FraudShadowObserverPort {

    static FraudShadowObserverPort noop() {
        return new FraudShadowObserverPort() {
            @Override public void recordCandidateReceived() {}
            @Override public void recordFeatureVectorProduced(FraudFeatureVector features) {}
            @Override public void recordCandidateSkipped(FraudFeatureVector features) {}
            @Override public void recordEvaluationSuccess(FraudEvaluation evaluation, Duration duration) {}
            @Override public void recordEvaluationDuplicate(FraudFeatureVector features) {}
            @Override public void recordEvaluationFailure(FraudShadowFailureStage stage, FraudFeatureVector features) {}
            @Override public void recordReplaySuccess(FraudReplayComparison comparison) {}
            @Override public void recordReplayMismatch(FraudReplayComparison comparison) {}
            @Override public void recordReplayFailure() {}
            @Override public void recordSchedulerCandidates(int count) {}
            @Override public void recordSchedulerCompleted(int count) {}
            @Override public void recordSchedulerFailed() {}
            @Override public void recordProcessingResult(FraudShadowProcessingResult result) {}
        };
    }

    void recordCandidateReceived();

    void recordFeatureVectorProduced(FraudFeatureVector features);

    void recordCandidateSkipped(FraudFeatureVector features);

    void recordEvaluationSuccess(FraudEvaluation evaluation, Duration duration);

    void recordEvaluationDuplicate(FraudFeatureVector features);

    void recordEvaluationFailure(FraudShadowFailureStage stage, FraudFeatureVector features);

    void recordReplaySuccess(FraudReplayComparison comparison);

    void recordReplayMismatch(FraudReplayComparison comparison);

    void recordReplayFailure();

    void recordSchedulerCandidates(int count);

    void recordSchedulerCompleted(int count);

    void recordSchedulerFailed();

    void recordProcessingResult(FraudShadowProcessingResult result);
}
