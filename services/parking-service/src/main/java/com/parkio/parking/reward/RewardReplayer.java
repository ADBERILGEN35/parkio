package com.parkio.parking.reward;

import java.util.Objects;

/** Offline-only replay for immutable pending reward intents. */
public final class RewardReplayer {

    private final RewardEngine engine = new RewardEngine();

    public RewardReplayComparison replay(PendingRewardIntent intent) {
        Objects.requireNonNull(intent, "intent");
        RewardEvaluation replayed = engine.evaluate(
                intent.contribution(),
                new RewardEvaluationContext(
                        intent.evaluatedAt(),
                        intent.rewardPolicyVersion(),
                        intent.snapshotSchemaVersion()));
        return new RewardReplayComparison(intent, replayed, replayed.equals(intent.evaluation()));
    }
}
