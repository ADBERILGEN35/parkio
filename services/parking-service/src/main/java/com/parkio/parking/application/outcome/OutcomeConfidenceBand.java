package com.parkio.parking.application.outcome;

import com.parkio.parking.outcome.confidence.OutcomeConfidence;

public enum OutcomeConfidenceBand {
    VERY_LOW,
    LOW,
    MEDIUM,
    HIGH,
    VERY_HIGH;

    public static OutcomeConfidenceBand from(OutcomeConfidence confidence) {
        int value = confidence.value();
        if (value < 20) {
            return VERY_LOW;
        }
        if (value < 40) {
            return LOW;
        }
        if (value < 60) {
            return MEDIUM;
        }
        if (value < 80) {
            return HIGH;
        }
        return VERY_HIGH;
    }
}