package com.parkio.parking.calibration;

import java.util.Optional;

public enum CalibrationObservationHorizon {
    AT_EVALUATION(null),
    SHORT_TERM("short-term"),
    MEDIUM_TERM("medium-term");

    private final String durationName;

    CalibrationObservationHorizon(String durationName) {
        this.durationName = durationName;
    }

    public Optional<String> durationName() {
        return Optional.ofNullable(durationName);
    }
}