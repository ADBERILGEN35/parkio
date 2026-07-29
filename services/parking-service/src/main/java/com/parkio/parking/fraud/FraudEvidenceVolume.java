package com.parkio.parking.fraud;

/** Effective eligible evidence volume used for confidence, separate from risk. */
public record FraudEvidenceVolume(int count) {

    public FraudEvidenceVolume {
        if (count < 0) {
            throw new IllegalArgumentException("evidence volume must be non-negative");
        }
    }

    public static FraudEvidenceVolume of(int count) {
        return new FraudEvidenceVolume(count);
    }
}
