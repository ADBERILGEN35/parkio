package com.parkio.parking.application;

/** Raised when an append-only calibration observation insert conflicts with an existing logical observation. */
public final class DuplicateCalibrationObservationException extends RuntimeException {

    public DuplicateCalibrationObservationException(String message) {
        super(message);
    }
}
