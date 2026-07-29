package com.parkio.parking.reward;

/** Immutable reward policy and bounded arithmetic configuration. */
public record RewardPolicyConfig(
        String policyVersion,
        int reporterBasePoints,
        int directAttributionMultiplier,
        int strongAttributionMultiplier,
        int partialAttributionMultiplier,
        int highConfidenceMultiplier,
        int mediumConfidenceMultiplier,
        int lowConfidenceMultiplier,
        int minimumReward,
        int maximumRewardPerContribution) {

    public static final String POLICY_VERSION = "reward-policy-v1";
    public static final int BASIS_POINTS = 10_000;

    public RewardPolicyConfig {
        if (reporterBasePoints < 0) {
            throw new IllegalArgumentException("reporterBasePoints must be non-negative");
        }
        validateMultiplier("directAttributionMultiplier", directAttributionMultiplier);
        validateMultiplier("strongAttributionMultiplier", strongAttributionMultiplier);
        validateMultiplier("partialAttributionMultiplier", partialAttributionMultiplier);
        validateMultiplier("highConfidenceMultiplier", highConfidenceMultiplier);
        validateMultiplier("mediumConfidenceMultiplier", mediumConfidenceMultiplier);
        validateMultiplier("lowConfidenceMultiplier", lowConfidenceMultiplier);
        if (minimumReward < 0) {
            throw new IllegalArgumentException("minimumReward must be non-negative");
        }
        if (maximumRewardPerContribution < minimumReward) {
            throw new IllegalArgumentException("maximumRewardPerContribution must be >= minimumReward");
        }
        if (strongAttributionMultiplier > directAttributionMultiplier) {
            throw new IllegalArgumentException("strongAttributionMultiplier must be <= directAttributionMultiplier");
        }
        if (partialAttributionMultiplier > strongAttributionMultiplier) {
            throw new IllegalArgumentException("partialAttributionMultiplier must be <= strongAttributionMultiplier");
        }
        if (lowConfidenceMultiplier > mediumConfidenceMultiplier
                || mediumConfidenceMultiplier > highConfidenceMultiplier) {
            throw new IllegalArgumentException("confidence multipliers must be monotonic");
        }
    }

    public static RewardPolicyConfig referenceV1() {
        return new RewardPolicyConfig(
                POLICY_VERSION,
                20,
                BASIS_POINTS,
                5_000,
                2_500,
                BASIS_POINTS,
                7_500,
                5_000,
                1,
                20);
    }

    private static void validateMultiplier(String name, int multiplier) {
        if (multiplier < 0 || multiplier > BASIS_POINTS) {
            throw new IllegalArgumentException(name + " must be between 0 and " + BASIS_POINTS);
        }
    }
}
