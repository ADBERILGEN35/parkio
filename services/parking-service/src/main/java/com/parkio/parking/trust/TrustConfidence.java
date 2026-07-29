package com.parkio.parking.trust;

/** Bounded trust confidence in basis points {@code 0..10000}. */
public record TrustConfidence(int basisPoints) {

    public static final int MIN = 0;
    public static final int MAX = 10_000;

    public TrustConfidence {
        if (basisPoints < MIN || basisPoints > MAX) {
            throw new IllegalArgumentException(
                    "TrustConfidence must be between " + MIN + " and " + MAX + ", got " + basisPoints);
        }
    }

    public static TrustConfidence of(int basisPoints) {
        return new TrustConfidence(basisPoints);
    }
}

