package com.parkio.parking.application.port;

import com.parkio.parking.application.reward.RewardShadowFailureStage;
import com.parkio.parking.application.reward.RewardShadowProcessingResult;
import com.parkio.parking.reward.RewardContribution;
import com.parkio.parking.reward.RewardEvaluation;
import com.parkio.parking.reward.RewardReplayComparison;
import java.time.Duration;

/** Bounded observability for reward shadow processing. */
public interface RewardShadowObserverPort {

    static RewardShadowObserverPort noop() {
        return new RewardShadowObserverPort() {
            @Override public void recordOutcomeReceived() {}
            @Override public void recordContributionProduced(RewardContribution contribution) {}
            @Override public void recordContributionSkipped(RewardContribution contribution) {}
            @Override public void recordEvaluationSuccess(RewardEvaluation evaluation, Duration duration) {}
            @Override public void recordEvaluationDuplicate(RewardContribution contribution) {}
            @Override public void recordEvaluationFailure(RewardShadowFailureStage stage, RewardContribution contribution) {}
            @Override public void recordReplaySuccess(RewardReplayComparison comparison) {}
            @Override public void recordReplayMismatch(RewardReplayComparison comparison) {}
            @Override public void recordReplayFailure() {}
            @Override public void recordSchedulerCandidates(int count) {}
            @Override public void recordSchedulerCompleted(int count) {}
            @Override public void recordSchedulerFailed() {}
        };
    }

    void recordOutcomeReceived();

    void recordContributionProduced(RewardContribution contribution);

    void recordContributionSkipped(RewardContribution contribution);

    void recordEvaluationSuccess(RewardEvaluation evaluation, Duration duration);

    void recordEvaluationDuplicate(RewardContribution contribution);

    void recordEvaluationFailure(RewardShadowFailureStage stage, RewardContribution contribution);

    void recordReplaySuccess(RewardReplayComparison comparison);

    void recordReplayMismatch(RewardReplayComparison comparison);

    void recordReplayFailure();

    void recordSchedulerCandidates(int count);

    void recordSchedulerCompleted(int count);

    void recordSchedulerFailed();

    default void recordProcessingResult(RewardShadowProcessingResult result) {}
}
