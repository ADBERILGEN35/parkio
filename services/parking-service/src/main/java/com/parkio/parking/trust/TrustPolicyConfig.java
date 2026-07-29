package com.parkio.parking.trust;

import java.util.Objects;

/** Immutable trust-policy reference configuration. */
public final class TrustPolicyConfig {

    public static final String POLICY_VERSION = "trust-policy-v1";
    public static final int BASIS_POINTS = 10_000;

    private final String policyVersion;
    private final int priorPositiveMass;
    private final int priorNegativeMass;
    private final int confidenceSaturationMass;
    private final int confirmedCorrectWeight;
    private final int likelyCorrectWeight;
    private final int confirmedIncorrectWeight;
    private final int likelyIncorrectWeight;
    private final int maxEvidenceImpact;
    private final int minimumNegativeConfidence;
    private final int directAttributionMultiplier;
    private final int strongAttributionMultiplier;
    private final int partialAttributionMultiplier;
    private final int highConfidenceMultiplier;
    private final int mediumConfidenceMultiplier;
    private final int lowConfidenceMultiplier;
    private final int lowConfidenceThreshold;
    private final int establishedConfidenceThreshold;
    private final int highConfidenceThreshold;
    private final int highConfidenceMinimumEvidenceCount;

    public TrustPolicyConfig(
            String policyVersion,
            int priorPositiveMass,
            int priorNegativeMass,
            int confidenceSaturationMass,
            int confirmedCorrectWeight,
            int likelyCorrectWeight,
            int confirmedIncorrectWeight,
            int likelyIncorrectWeight,
            int maxEvidenceImpact,
            int minimumNegativeConfidence,
            int directAttributionMultiplier,
            int strongAttributionMultiplier,
            int partialAttributionMultiplier,
            int highConfidenceMultiplier,
            int mediumConfidenceMultiplier,
            int lowConfidenceMultiplier,
            int lowConfidenceThreshold,
            int establishedConfidenceThreshold,
            int highConfidenceThreshold,
            int highConfidenceMinimumEvidenceCount) {
        this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
        this.priorPositiveMass = requireNonNegative(priorPositiveMass, "priorPositiveMass");
        this.priorNegativeMass = requireNonNegative(priorNegativeMass, "priorNegativeMass");
        this.confidenceSaturationMass = requirePositive(confidenceSaturationMass, "confidenceSaturationMass");
        this.confirmedCorrectWeight = requirePositive(confirmedCorrectWeight, "confirmedCorrectWeight");
        this.likelyCorrectWeight = requirePositive(likelyCorrectWeight, "likelyCorrectWeight");
        this.confirmedIncorrectWeight = requirePositive(confirmedIncorrectWeight, "confirmedIncorrectWeight");
        this.likelyIncorrectWeight = requirePositive(likelyIncorrectWeight, "likelyIncorrectWeight");
        this.maxEvidenceImpact = requirePositive(maxEvidenceImpact, "maxEvidenceImpact");
        this.minimumNegativeConfidence = requireBounded(minimumNegativeConfidence, "minimumNegativeConfidence");
        this.directAttributionMultiplier = requireMultiplier(directAttributionMultiplier, "directAttributionMultiplier");
        this.strongAttributionMultiplier = requireMultiplier(strongAttributionMultiplier, "strongAttributionMultiplier");
        this.partialAttributionMultiplier = requireMultiplier(partialAttributionMultiplier, "partialAttributionMultiplier");
        this.highConfidenceMultiplier = requireMultiplier(highConfidenceMultiplier, "highConfidenceMultiplier");
        this.mediumConfidenceMultiplier = requireMultiplier(mediumConfidenceMultiplier, "mediumConfidenceMultiplier");
        this.lowConfidenceMultiplier = requireMultiplier(lowConfidenceMultiplier, "lowConfidenceMultiplier");
        this.lowConfidenceThreshold = requireBounded(lowConfidenceThreshold, "lowConfidenceThreshold");
        this.establishedConfidenceThreshold = requireBounded(establishedConfidenceThreshold, "establishedConfidenceThreshold");
        this.highConfidenceThreshold = requireBounded(highConfidenceThreshold, "highConfidenceThreshold");
        this.highConfidenceMinimumEvidenceCount = requirePositive(
                highConfidenceMinimumEvidenceCount, "highConfidenceMinimumEvidenceCount");
        if (priorPositiveMass != priorNegativeMass) {
            throw new IllegalArgumentException("cold-start prior must remain neutral");
        }
        if (likelyCorrectWeight >= confirmedCorrectWeight) {
            throw new IllegalArgumentException("likelyCorrectWeight must be lower than confirmedCorrectWeight");
        }
        if (likelyIncorrectWeight >= confirmedIncorrectWeight) {
            throw new IllegalArgumentException("likelyIncorrectWeight must be lower than confirmedIncorrectWeight");
        }
        if (strongAttributionMultiplier > directAttributionMultiplier
                || partialAttributionMultiplier > strongAttributionMultiplier) {
            throw new IllegalArgumentException("attribution multipliers must be monotonic");
        }
        if (lowConfidenceMultiplier > mediumConfidenceMultiplier
                || mediumConfidenceMultiplier > highConfidenceMultiplier) {
            throw new IllegalArgumentException("confidence multipliers must be monotonic");
        }
        if (lowConfidenceThreshold >= establishedConfidenceThreshold
                || establishedConfidenceThreshold > highConfidenceThreshold) {
            throw new IllegalArgumentException("trust level thresholds must be monotonic");
        }
    }

    public static TrustPolicyConfig referenceV1() {
        return new TrustPolicyConfig(
                POLICY_VERSION,
                2_000,
                2_000,
                12_000,
                1_400,
                800,
                1_200,
                600,
                1_500,
                70,
                10_000,
                8_000,
                4_000,
                10_000,
                8_500,
                6_500,
                2_500,
                6_000,
                8_500,
                8);
    }

    public String policyVersion() { return policyVersion; }
    public int priorPositiveMass() { return priorPositiveMass; }
    public int priorNegativeMass() { return priorNegativeMass; }
    public int confidenceSaturationMass() { return confidenceSaturationMass; }
    public int confirmedCorrectWeight() { return confirmedCorrectWeight; }
    public int likelyCorrectWeight() { return likelyCorrectWeight; }
    public int confirmedIncorrectWeight() { return confirmedIncorrectWeight; }
    public int likelyIncorrectWeight() { return likelyIncorrectWeight; }
    public int maxEvidenceImpact() { return maxEvidenceImpact; }
    public int minimumNegativeConfidence() { return minimumNegativeConfidence; }
    public int directAttributionMultiplier() { return directAttributionMultiplier; }
    public int strongAttributionMultiplier() { return strongAttributionMultiplier; }
    public int partialAttributionMultiplier() { return partialAttributionMultiplier; }
    public int highConfidenceMultiplier() { return highConfidenceMultiplier; }
    public int mediumConfidenceMultiplier() { return mediumConfidenceMultiplier; }
    public int lowConfidenceMultiplier() { return lowConfidenceMultiplier; }
    public int lowConfidenceThreshold() { return lowConfidenceThreshold; }
    public int establishedConfidenceThreshold() { return establishedConfidenceThreshold; }
    public int highConfidenceThreshold() { return highConfidenceThreshold; }
    public int highConfidenceMinimumEvidenceCount() { return highConfidenceMinimumEvidenceCount; }

    private static int requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static int requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }

    private static int requireBounded(int value, String field) {
        if (value < 0 || value > BASIS_POINTS) {
            throw new IllegalArgumentException(field + " must be between 0 and " + BASIS_POINTS);
        }
        return value;
    }

    private static int requireMultiplier(int value, String field) {
        return requireBounded(value, field);
    }
}

