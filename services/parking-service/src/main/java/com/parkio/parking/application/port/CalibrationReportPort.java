package com.parkio.parking.application.port;

import com.parkio.parking.calibration.CalibrationEngineType;
import com.parkio.parking.calibration.CalibrationReport;
import java.util.Optional;
import java.util.UUID;

/** Append-only calibration report boundary. */
public interface CalibrationReportPort {

    void append(CalibrationReport report);

    Optional<CalibrationReport> findByReportId(UUID reportId);

    Optional<CalibrationReport> findLatestByEngineAndPolicy(CalibrationEngineType engineType, String policyVersion);
}
