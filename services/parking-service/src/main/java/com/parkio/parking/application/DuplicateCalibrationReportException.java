package com.parkio.parking.application;

/** Raised when an append-only calibration report insert conflicts with an existing logical report. */
public final class DuplicateCalibrationReportException extends RuntimeException {

    public DuplicateCalibrationReportException(String message) {
        super(message);
    }
}
