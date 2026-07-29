package com.parkio.parking.reward;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Deterministic result of evaluating one contribution under one reward policy. */
public record RewardEvaluation(
        RewardContribution contribution,
        Disposition disposition,
        RewardAmount amount,
        RewardUnit rewardUnit,
        String decisiveRule,
        Set<RewardContribution.EligibilityReason> reasons,
        String rewardPolicyVersion,
        Instant evaluatedAt) {

    public RewardEvaluation {
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(rewardUnit, "rewardUnit");
        Objects.requireNonNull(decisiveRule, "decisiveRule");
        reasons = reasons == null || reasons.isEmpty()
                ? Set.of(contribution.primaryEligibilityReason())
                : Set.copyOf(reasons);
        Objects.requireNonNull(rewardPolicyVersion, "rewardPolicyVersion");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (disposition != Disposition.PENDING && !amount.isZero()) {
            throw new IllegalArgumentException("Non-pending dispositions must not carry a positive amount");
        }
    }

    public enum Disposition {
        PENDING,
        NO_REWARD,
        DEFERRED,
        INELIGIBLE,
        DUPLICATE,
        POLICY_UNSUPPORTED
    }
}
