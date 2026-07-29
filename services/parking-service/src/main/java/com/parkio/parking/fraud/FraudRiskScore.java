package com.parkio.parking.fraud;

import java.util.Objects;

/** Bounded fraud risk score in basis points (0-10_000). */
public record FraudRiskScore(int basisPoints) {

    public static final int MAX_BASIS_POINTS = 10_000;

    public FraudRiskScore {
        if (basisPoints < 0 || basisPoints > MAX_BASIS_POINTS) {
            throw new IllegalArgumentException("fraud risk must be between 0 and " + MAX_BASIS_POINTS);
        }
    }

    public static FraudRiskScore of(int basisPoints) {
        return new FraudRiskScore(basisPoints);
    }

    public static FraudRiskScore zero() {
        return new FraudRiskScore(0);
    }

    public FraudRiskScore cappedAdd(int delta, int cap) {
        if (delta < 0) {
            throw new IllegalArgumentException("delta must be non-negative");
        }
        return new FraudRiskScore(Math.min(cap, basisPoints + delta));
    }
}
