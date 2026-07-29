package com.parkio.parking.outcome.confidence;

/**
 * Integer confidence in an outcome classification, scale {@code 0..100}.
 */
public record OutcomeConfidence(int value) {

    public static final int MIN = 0;
    public static final int MAX = 100;

    public OutcomeConfidence {
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException(
                    "OutcomeConfidence must be between " + MIN + " and " + MAX + ", got " + value);
        }
    }

    public static OutcomeConfidence of(int value) {
        return new OutcomeConfidence(value);
    }
}