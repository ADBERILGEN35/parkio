package com.parkio.parking.application.port;

import com.parkio.parking.calibration.CalibrationEngineType;
import com.parkio.parking.calibration.CalibrationObservation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Append-only calibration observation boundary. */
public interface CalibrationObservationPort {

    void append(CalibrationObservation observation);

    Optional<CalibrationObservation> findByObservationId(UUID observationId);

    List<CalibrationObservation> findByEngineAndWindow(
            CalibrationEngineType engineType, Instant windowStart, Instant windowEnd);
}
