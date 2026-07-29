package com.parkio.parking.decision.score;

/**
 * Normalized evidence quality for one submission, scale {@code 0..100} (integer).
 *
 * <p>Distinct from {@link TrustScore} (actor-level, {@code 0.00..1.00}).
 * An unknown / uncomputed score MUST be represented as absence ({@code Optional.empty()}),
 * never as {@code 0}.
 *
 * <p>Contains no scoring algorithm.
 */
public record EvidenceScore(int value) {

    public static final int MIN = 0;
    public static final int MAX = 100;

    public EvidenceScore {
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException(
                    "EvidenceScore must be between " + MIN + " and " + MAX + " inclusive, got " + value);
        }
    }

    public static EvidenceScore of(int value) {
        return new EvidenceScore(value);
    }
}