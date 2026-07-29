package com.parkio.parking.reward;

import java.util.Objects;

/** Compares stored reward evaluation with offline replay. */
public record RewardReplayComparison(
        PendingRewardIntent intent,
        RewardEvaluation replayedEvaluation,
        boolean identical) {

    public RewardReplayComparison {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(replayedEvaluation, "replayedEvaluation");
    }
}
