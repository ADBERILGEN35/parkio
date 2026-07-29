package com.parkio.parking.application.port;

import com.parkio.parking.application.trust.TrustShadowFailureStage;
import com.parkio.parking.application.trust.TrustShadowProcessingResult;
import com.parkio.parking.trust.TrustEvaluation;
import com.parkio.parking.trust.TrustEvidence;
import com.parkio.parking.trust.TrustReplayComparison;
import java.time.Duration;

/** Bounded observability for trust shadow processing. */
public interface TrustShadowObserverPort {

    static TrustShadowObserverPort noop() {
        return new TrustShadowObserverPort() {
            @Override public void recordOutcomeReceived() {}
            @Override public void recordEvidenceProduced(TrustEvidence evidence) {}
            @Override public void recordEvidenceSkipped(TrustEvidence evidence) {}
            @Override public void recordUpdateSuccess(TrustEvaluation evaluation, Duration duration) {}
            @Override public void recordUpdateDuplicate(TrustEvidence evidence) {}
            @Override public void recordUpdateFailure(TrustShadowFailureStage stage, TrustEvidence evidence) {}
            @Override public void recordReplaySuccess(TrustReplayComparison comparison) {}
            @Override public void recordReplayMismatch(TrustReplayComparison comparison) {}
            @Override public void recordReplayFailure() {}
            @Override public void recordSchedulerCandidates(int count) {}
            @Override public void recordSchedulerCompleted(int count) {}
            @Override public void recordSchedulerFailed() {}
        };
    }

    void recordOutcomeReceived();

    void recordEvidenceProduced(TrustEvidence evidence);

    void recordEvidenceSkipped(TrustEvidence evidence);

    void recordUpdateSuccess(TrustEvaluation evaluation, Duration duration);

    void recordUpdateDuplicate(TrustEvidence evidence);

    void recordUpdateFailure(TrustShadowFailureStage stage, TrustEvidence evidence);

    void recordReplaySuccess(TrustReplayComparison comparison);

    void recordReplayMismatch(TrustReplayComparison comparison);

    void recordReplayFailure();

    void recordSchedulerCandidates(int count);

    void recordSchedulerCompleted(int count);

    void recordSchedulerFailed();

    default void recordProcessingResult(TrustShadowProcessingResult result) {}
}

