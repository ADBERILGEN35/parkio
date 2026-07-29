package com.parkio.parking.availability.policy;

import java.util.Objects;

/**
 * Configurable decay thresholds for availability scoring and classification.
 *
 * <p>Ratios are expressed in basis points ({@code 0..10000}) of the advertised
 * lifetime window so policies remain independent of hard-coded minute constants.
 * Integer arithmetic only.
 */
public final class AvailabilityPolicyConfig {

    public static final AvailabilityPolicyVersion POLICY_VERSION = AvailabilityPolicyVersion.of("availability-v1");

    /** Remaining TTL at or above this ratio maps to {@code AVAILABLE}. */
    public static final int AVAILABLE_REMAINING_BPS = 7500;
    /** Remaining TTL at or above this ratio maps to {@code LIKELY_AVAILABLE}. */
    public static final int LIKELY_AVAILABLE_REMAINING_BPS = 5000;
    /** Remaining TTL at or above this ratio maps to {@code UNKNOWN}. */
    public static final int UNKNOWN_REMAINING_BPS = 2500;

    /** Elapsed lifetime at or below this ratio is {@code FRESH}. */
    public static final int FRESH_ELAPSED_BPS = 2500;
    /** Elapsed lifetime at or below this ratio is {@code AGING}. */
    public static final int AGING_ELAPSED_BPS = 6000;
    /** Elapsed lifetime at or below this ratio is {@code STALE}. */
    public static final int STALE_ELAPSED_BPS = 9000;

    /** Confidence score below this (0..10000 bps) contributes toward occupancy. */
    public static final int LOW_CONFIDENCE_BPS = 5000;

    /** Score boost when at least one community verification exists. */
    public static final int VERIFICATION_SCORE_BOOST = 10;

    /** Score penalty per filled report before terminal fill. */
    public static final int FILLED_REPORT_SCORE_PENALTY = 20;

    private static final AvailabilityPolicyConfig INSTANCE = new AvailabilityPolicyConfig();

    private AvailabilityPolicyConfig() {}

    public static AvailabilityPolicyConfig referenceV1() {
        return INSTANCE;
    }

    public AvailabilityPolicyVersion policyVersion() {
        return POLICY_VERSION;
    }

    public int clampBasisPoints(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 10_000) {
            return 10_000;
        }
        return value;
    }

    /** Half-up integer division of {@code numerator / denominator}. */
    public static int divideHalfUp(long numerator, long denominator) {
        Objects.requireNonNull(denominator);
        if (denominator <= 0) {
            throw new IllegalArgumentException("denominator must be positive");
        }
        if (numerator >= 0) {
            return (int) ((numerator + denominator / 2) / denominator);
        }
        return (int) -(((-numerator) + denominator / 2) / denominator);
    }

    public static int clampScore(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 100) {
            return 100;
        }
        return value;
    }
}
