package com.parkio.parking.exposure;

import java.util.Objects;

/** Immutable versioned exposure policy configuration. */
public final class ExposurePolicyConfig {

    public static final String POLICY_VERSION = "exposure-policy-v1";
    public static final int BASIS_POINTS = 10_000;
    public static final int MAX_TOTAL_SCORE = 10_000;

    private final String policyVersion;
    private final int distanceMaxContribution;
    private final int freshnessMaxContribution;
    private final int availabilityMaxContribution;
    private final int vehicleMaxContribution;
    private final int publicationMaxContribution;
    private final int trustMaxContribution;
    private final int prioritizeThreshold;
    private final int standardThreshold;
    private final int deprioritizeThreshold;
    private final int minimumEligibleScore;

    private ExposurePolicyConfig(
            String policyVersion,
            int distanceMaxContribution,
            int freshnessMaxContribution,
            int availabilityMaxContribution,
            int vehicleMaxContribution,
            int publicationMaxContribution,
            int trustMaxContribution,
            int prioritizeThreshold,
            int standardThreshold,
            int deprioritizeThreshold,
            int minimumEligibleScore) {
        this.policyVersion = policyVersion;
        this.distanceMaxContribution = distanceMaxContribution;
        this.freshnessMaxContribution = freshnessMaxContribution;
        this.availabilityMaxContribution = availabilityMaxContribution;
        this.vehicleMaxContribution = vehicleMaxContribution;
        this.publicationMaxContribution = publicationMaxContribution;
        this.trustMaxContribution = trustMaxContribution;
        this.prioritizeThreshold = prioritizeThreshold;
        this.standardThreshold = standardThreshold;
        this.deprioritizeThreshold = deprioritizeThreshold;
        this.minimumEligibleScore = minimumEligibleScore;
        validate();
    }

    public static ExposurePolicyConfig referenceV1() {
        return new ExposurePolicyConfig(
                POLICY_VERSION,
                5_000,
                2_500,
                1_500,
                500,
                500,
                0,
                7_500,
                4_000,
                2_000,
                500);
    }

    public String policyVersion() {
        return policyVersion;
    }

    public int distanceMaxContribution() {
        return distanceMaxContribution;
    }

    public int freshnessMaxContribution() {
        return freshnessMaxContribution;
    }

    public int availabilityMaxContribution() {
        return availabilityMaxContribution;
    }

    public int vehicleMaxContribution() {
        return vehicleMaxContribution;
    }

    public int publicationMaxContribution() {
        return publicationMaxContribution;
    }

    public int trustMaxContribution() {
        return trustMaxContribution;
    }

    public int prioritizeThreshold() {
        return prioritizeThreshold;
    }

    public int standardThreshold() {
        return standardThreshold;
    }

    public int deprioritizeThreshold() {
        return deprioritizeThreshold;
    }

    public int minimumEligibleScore() {
        return minimumEligibleScore;
    }

    private void validate() {
        if (distanceMaxContribution < 0 || freshnessMaxContribution < 0
                || availabilityMaxContribution < 0 || vehicleMaxContribution < 0
                || publicationMaxContribution < 0 || trustMaxContribution < 0) {
            throw new IllegalArgumentException("component maximums must be non-negative");
        }
        int sum = distanceMaxContribution + freshnessMaxContribution + availabilityMaxContribution
                + vehicleMaxContribution + publicationMaxContribution + trustMaxContribution;
        if (sum > MAX_TOTAL_SCORE) {
            throw new IllegalArgumentException("component maximums exceed total score bound");
        }
        if (prioritizeThreshold < standardThreshold
                || standardThreshold < deprioritizeThreshold
                || deprioritizeThreshold < minimumEligibleScore) {
            throw new IllegalArgumentException("disposition thresholds must be monotonic descending");
        }
    }
}
