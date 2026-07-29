package com.parkio.parking.fraud;

import java.time.Duration;
import java.util.Objects;

/** Immutable versioned fraud policy configuration. */
public final class FraudPolicyConfig {

    public static final String POLICY_VERSION = "fraud-policy-v1";
    public static final int BASIS_POINTS = 10_000;
    public static final Duration ROLLING_WINDOW = Duration.ofDays(7);

    private final String policyVersion;
    private final int minimumEvidenceVolume;
    private final int minimumEvidenceForElevated;
    private final int hardAnomalyConfirmedIncorrectThreshold;
    private final int confirmedIncorrectWeight;
    private final int likelyIncorrectWeight;
    private final int confirmedCorrectMitigation;
    private final int maxCategoryContribution;
    private final int maxSingleEventContribution;
    private final int maxTotalRisk;
    private final int singleIncorrectRiskCap;
    private final int elevatedRiskThreshold;
    private final int highRiskThreshold;
    private final int criticalRiskThreshold;
    private final int mediumConfidenceEvidenceThreshold;
    private final int highConfidenceEvidenceThreshold;

    public FraudPolicyConfig(
            String policyVersion,
            int minimumEvidenceVolume,
            int minimumEvidenceForElevated,
            int hardAnomalyConfirmedIncorrectThreshold,
            int confirmedIncorrectWeight,
            int likelyIncorrectWeight,
            int confirmedCorrectMitigation,
            int maxCategoryContribution,
            int maxSingleEventContribution,
            int maxTotalRisk,
            int singleIncorrectRiskCap,
            int elevatedRiskThreshold,
            int highRiskThreshold,
            int criticalRiskThreshold,
            int mediumConfidenceEvidenceThreshold,
            int highConfidenceEvidenceThreshold) {
        this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
        this.minimumEvidenceVolume = requireNonNegative(minimumEvidenceVolume, "minimumEvidenceVolume");
        this.minimumEvidenceForElevated = requirePositive(minimumEvidenceForElevated, "minimumEvidenceForElevated");
        this.hardAnomalyConfirmedIncorrectThreshold =
                requirePositive(hardAnomalyConfirmedIncorrectThreshold, "hardAnomalyConfirmedIncorrectThreshold");
        this.confirmedIncorrectWeight = requirePositive(confirmedIncorrectWeight, "confirmedIncorrectWeight");
        this.likelyIncorrectWeight = requirePositive(likelyIncorrectWeight, "likelyIncorrectWeight");
        this.confirmedCorrectMitigation = requireNonNegative(confirmedCorrectMitigation, "confirmedCorrectMitigation");
        this.maxCategoryContribution = requirePositive(maxCategoryContribution, "maxCategoryContribution");
        this.maxSingleEventContribution = requirePositive(maxSingleEventContribution, "maxSingleEventContribution");
        this.maxTotalRisk = requirePositive(maxTotalRisk, "maxTotalRisk");
        this.singleIncorrectRiskCap = requireBounded(singleIncorrectRiskCap, "singleIncorrectRiskCap");
        this.elevatedRiskThreshold = requireBounded(elevatedRiskThreshold, "elevatedRiskThreshold");
        this.highRiskThreshold = requireBounded(highRiskThreshold, "highRiskThreshold");
        this.criticalRiskThreshold = requireBounded(criticalRiskThreshold, "criticalRiskThreshold");
        this.mediumConfidenceEvidenceThreshold =
                requirePositive(mediumConfidenceEvidenceThreshold, "mediumConfidenceEvidenceThreshold");
        this.highConfidenceEvidenceThreshold =
                requirePositive(highConfidenceEvidenceThreshold, "highConfidenceEvidenceThreshold");
        if (likelyIncorrectWeight >= confirmedIncorrectWeight) {
            throw new IllegalArgumentException("likelyIncorrectWeight must be lower than confirmedIncorrectWeight");
        }
        if (singleIncorrectRiskCap > elevatedRiskThreshold) {
            throw new IllegalArgumentException("singleIncorrectRiskCap must not exceed elevatedRiskThreshold");
        }
        if (!(elevatedRiskThreshold < highRiskThreshold && highRiskThreshold < criticalRiskThreshold)) {
            throw new IllegalArgumentException("risk thresholds must be monotonic");
        }
        if (mediumConfidenceEvidenceThreshold >= highConfidenceEvidenceThreshold) {
            throw new IllegalArgumentException("confidence evidence thresholds must be monotonic");
        }
        if (minimumEvidenceForElevated < minimumEvidenceVolume) {
            throw new IllegalArgumentException("minimumEvidenceForElevated must be >= minimumEvidenceVolume");
        }
    }

    public static FraudPolicyConfig referenceV1() {
        return new FraudPolicyConfig(
                POLICY_VERSION,
                1,
                2,
                4,
                2_500,
                900,
                400,
                6_000,
                2_500,
                8_500,
                1_800,
                2_000,
                4_500,
                7_000,
                2,
                5);
    }

    public String policyVersion() {
        return policyVersion;
    }

    public int minimumEvidenceVolume() {
        return minimumEvidenceVolume;
    }

    public int minimumEvidenceForElevated() {
        return minimumEvidenceForElevated;
    }

    public int hardAnomalyConfirmedIncorrectThreshold() {
        return hardAnomalyConfirmedIncorrectThreshold;
    }

    public int confirmedIncorrectWeight() {
        return confirmedIncorrectWeight;
    }

    public int likelyIncorrectWeight() {
        return likelyIncorrectWeight;
    }

    public int confirmedCorrectMitigation() {
        return confirmedCorrectMitigation;
    }

    public int maxCategoryContribution() {
        return maxCategoryContribution;
    }

    public int maxSingleEventContribution() {
        return maxSingleEventContribution;
    }

    public int maxTotalRisk() {
        return maxTotalRisk;
    }

    public int singleIncorrectRiskCap() {
        return singleIncorrectRiskCap;
    }

    public int elevatedRiskThreshold() {
        return elevatedRiskThreshold;
    }

    public int highRiskThreshold() {
        return highRiskThreshold;
    }

    public int criticalRiskThreshold() {
        return criticalRiskThreshold;
    }

    public int mediumConfidenceEvidenceThreshold() {
        return mediumConfidenceEvidenceThreshold;
    }

    public int highConfidenceEvidenceThreshold() {
        return highConfidenceEvidenceThreshold;
    }

    private static int requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int requireBounded(int value, String name) {
        if (value < 0 || value > BASIS_POINTS) {
            throw new IllegalArgumentException(name + " must be between 0 and " + BASIS_POINTS);
        }
        return value;
    }
}
