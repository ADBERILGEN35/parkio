package com.parkio.parking.reward;

import java.time.Instant;
import java.util.Objects;

/** Deterministic evaluation context for reward calculation. */
public record RewardEvaluationContext(
        Instant evaluatedAt,
        String rewardPolicyVersion,
        RewardSnapshotSchemaVersion snapshotSchemaVersion) {

    public RewardEvaluationContext {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(rewardPolicyVersion, "rewardPolicyVersion");
        Objects.requireNonNull(snapshotSchemaVersion, "snapshotSchemaVersion");
    }
}
