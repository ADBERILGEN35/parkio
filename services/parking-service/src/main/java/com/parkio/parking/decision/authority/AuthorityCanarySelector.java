package com.parkio.parking.decision.authority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic authority canary cohort selection ({@link AuthorityAlgorithmVersion#V1}).
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Normalize input as {@code authority-canary-v1|{spotId}|{evaluationId}} UTF-8</li>
 *   <li>SHA-256 digest</li>
 *   <li>Interpret first 4 bytes as unsigned big-endian int</li>
 *   <li>{@code bucket = value % 10000} → range {@code [0, 9999]}</li>
 *   <li>Selected when {@code bucket < canaryPercentage * 100}</li>
 * </ol>
 *
 * <p>Examples: 0% → never; 1% → buckets {@code [0, 99]}; 100% → all buckets.
 * Stable across retries and replicas. No Random, System.currentTimeMillis,
 * Object.hashCode, or String.hashCode.
 */
public final class AuthorityCanarySelector {

    public static final int BUCKET_MODULUS = 10_000;

    private AuthorityCanarySelector() {}

    public static int bucket(UUID parkingSpotId, UUID evaluationId) {
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(evaluationId, "evaluationId");
        String material = AuthorityAlgorithmVersion.V1
                + '|'
                + parkingSpotId
                + '|'
                + evaluationId;
        byte[] digest = sha256(material.getBytes(StandardCharsets.UTF_8));
        int unsigned = ((digest[0] & 0xff) << 24)
                | ((digest[1] & 0xff) << 16)
                | ((digest[2] & 0xff) << 8)
                | (digest[3] & 0xff);
        // Mask to avoid negative remainder quirks; modulus keeps range [0, 9999].
        return Math.floorMod(unsigned, BUCKET_MODULUS);
    }

    /**
     * @param canaryPercentage integer percent in {@code [0, 100]}
     */
    public static boolean isSelected(int bucket, int canaryPercentage) {
        requirePercentage(canaryPercentage);
        if (bucket < 0 || bucket >= BUCKET_MODULUS) {
            throw new IllegalArgumentException("bucket must be in [0, " + (BUCKET_MODULUS - 1) + "]");
        }
        int threshold = canaryPercentage * 100;
        return bucket < threshold;
    }

    public static boolean isSelected(UUID parkingSpotId, UUID evaluationId, int canaryPercentage) {
        return isSelected(bucket(parkingSpotId, evaluationId), canaryPercentage);
    }

    public static void requirePercentage(int canaryPercentage) {
        if (canaryPercentage < 0 || canaryPercentage > 100) {
            throw new IllegalArgumentException(
                    "canaryPercentage must be between 0 and 100 inclusive, got " + canaryPercentage);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}