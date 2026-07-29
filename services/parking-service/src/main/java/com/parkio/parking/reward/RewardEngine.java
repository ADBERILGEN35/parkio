package com.parkio.parking.reward;

import com.parkio.parking.outcome.OutcomeClassification;
import java.util.Objects;
import java.util.Set;

/** Pure deterministic pending-reward engine. */
public final class RewardEngine {

    public RewardEvaluation evaluate(RewardContribution contribution, RewardEvaluationContext context) {
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(context, "context");
        RewardPolicyConfig policy = policyFor(context.rewardPolicyVersion());

        RewardEvaluation.Disposition disposition = dispositionFor(contribution);
        if (disposition != RewardEvaluation.Disposition.PENDING) {
            return new RewardEvaluation(
                    contribution,
                    disposition,
                    RewardAmount.zero(),
                    RewardUnit.POINTS,
                    decisiveRule(contribution, disposition),
                    Set.of(contribution.primaryEligibilityReason()),
                    policy.policyVersion(),
                    context.evaluatedAt());
        }

        int amount = computeAmount(contribution, policy);
        if (amount < policy.minimumReward()) {
            return new RewardEvaluation(
                    contribution,
                    RewardEvaluation.Disposition.NO_REWARD,
                    RewardAmount.zero(),
                    RewardUnit.POINTS,
                    "AMOUNT_BELOW_MINIMUM",
                    Set.of(contribution.primaryEligibilityReason()),
                    policy.policyVersion(),
                    context.evaluatedAt());
        }

        return new RewardEvaluation(
                contribution,
                RewardEvaluation.Disposition.PENDING,
                new RewardAmount(amount),
                RewardUnit.POINTS,
                decisiveRule(contribution, RewardEvaluation.Disposition.PENDING),
                Set.of(contribution.primaryEligibilityReason()),
                policy.policyVersion(),
                context.evaluatedAt());
    }

    private static RewardPolicyConfig policyFor(String version) {
        if (RewardPolicyConfig.POLICY_VERSION.equals(version)) {
            return RewardPolicyConfig.referenceV1();
        }
        throw new UnsupportedRewardPolicyVersionException("Unsupported reward policy version: " + version);
    }

    private static RewardEvaluation.Disposition dispositionFor(RewardContribution contribution) {
        if (contribution.eligibility() == RewardContribution.Eligibility.ELIGIBLE
                && contribution.outcomeClassification() == OutcomeClassification.CONFIRMED_CORRECT) {
            return RewardEvaluation.Disposition.PENDING;
        }
        return switch (contribution.eligibility()) {
            case ELIGIBLE, OUTCOME_NOT_REWARDABLE, AMBIGUOUS_ATTRIBUTION,
                    UNSUPPORTED_ROLE, SELF_CONFIRMATION_BLOCKED -> RewardEvaluation.Disposition.NO_REWARD;
            case DEFERRED_FINALITY -> RewardEvaluation.Disposition.DEFERRED;
        };
    }

    private static int computeAmount(RewardContribution contribution, RewardPolicyConfig policy) {
        int base = switch (contribution.contributionRole()) {
            case REPORTER -> policy.reporterBasePoints();
            default -> 0;
        };
        int attributed = multiplyBasisPoints(base, attributionMultiplier(contribution, policy));
        int confidenceAdjusted = multiplyBasisPoints(attributed, confidenceMultiplier(contribution, policy));
        return Math.min(confidenceAdjusted, policy.maximumRewardPerContribution());
    }

    private static int attributionMultiplier(RewardContribution contribution, RewardPolicyConfig policy) {
        return switch (contribution.attributionQuality()) {
            case DIRECT -> policy.directAttributionMultiplier();
            case STRONG -> policy.strongAttributionMultiplier();
            case PARTIAL -> policy.partialAttributionMultiplier();
            case AMBIGUOUS, NONE -> 0;
        };
    }

    private static int confidenceMultiplier(RewardContribution contribution, RewardPolicyConfig policy) {
        return switch (contribution.outcomeConfidenceBand()) {
            case "HIGH" -> policy.highConfidenceMultiplier();
            case "MEDIUM" -> policy.mediumConfidenceMultiplier();
            default -> policy.lowConfidenceMultiplier();
        };
    }

    private static String decisiveRule(RewardContribution contribution, RewardEvaluation.Disposition disposition) {
        return switch (disposition) {
            case PENDING -> "PENDING_" + contribution.primaryEligibilityReason().name();
            case NO_REWARD -> "NO_REWARD_" + contribution.primaryEligibilityReason().name();
            case DEFERRED -> "DEFERRED_" + contribution.primaryEligibilityReason().name();
            case INELIGIBLE -> "INELIGIBLE_" + contribution.primaryEligibilityReason().name();
            case DUPLICATE -> "DUPLICATE";
            case POLICY_UNSUPPORTED -> "POLICY_UNSUPPORTED";
        };
    }

    private static int multiplyBasisPoints(int value, int multiplier) {
        long raw = (long) value * multiplier;
        return divideRounded(raw, RewardPolicyConfig.BASIS_POINTS);
    }

    private static int divideRounded(long numerator, long denominator) {
        if (denominator <= 0) {
            throw new IllegalArgumentException("denominator must be positive");
        }
        if (numerator < 0) {
            throw new IllegalArgumentException("numerator must be non-negative");
        }
        if (numerator > Integer.MAX_VALUE * denominator) {
            throw new IllegalArgumentException("reward arithmetic overflow");
        }
        return (int) ((numerator + (denominator / 2)) / denominator);
    }
}
