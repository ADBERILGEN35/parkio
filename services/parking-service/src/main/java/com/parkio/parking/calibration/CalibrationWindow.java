package com.parkio.parking.calibration;

import java.time.Instant;
import java.util.Objects;

public record CalibrationWindow(Instant start, Instant end) {

    public CalibrationWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end must not be before start");
        }
    }
}