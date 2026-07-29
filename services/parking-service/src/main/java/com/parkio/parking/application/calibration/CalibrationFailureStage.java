package com.parkio.parking.application.calibration;

/** Failure stage for continuous calibration processing. */
public enum CalibrationFailureStage {
    SOURCE_READ,
    OBSERVATION_BUILD,
    OBSERVATION_APPEND,
    REPORT_GENERATION,
    READINESS_ASSESSMENT,
    REPLAY,
    OBSERVABILITY
}
