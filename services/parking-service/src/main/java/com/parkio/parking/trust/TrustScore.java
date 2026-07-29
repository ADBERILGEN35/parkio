package com.parkio.parking.trust;

/** Bounded trust estimate in basis points {@code 0..10000}. */
public record TrustScore(int basisPoints) {

    public static final int MIN = 0;
    public static final int MAX = 10_000;

    public TrustScore {
        if (basisPoints < MIN || basisPoints > MAX) {
            throw new IllegalArgumentException(
                    "TrustScore must be between " + MIN + " and " + MAX + ", got " + basisPoints);
        }
    }

    public static TrustScore of(int basisPoints) {
        return new TrustScore(basisPoints);
    }
}

