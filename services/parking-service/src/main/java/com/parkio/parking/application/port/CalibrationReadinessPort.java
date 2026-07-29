package com.parkio.parking.application.port;

import com.parkio.parking.calibration.CalibrationReadinessAssessment;
import java.util.Optional;
import java.util.UUID;

/** Append-only calibration readiness assessment boundary. */
public interface CalibrationReadinessPort {

    void append(CalibrationReadinessAssessment assessment);

    Optional<CalibrationReadinessAssessment> findByAssessmentId(UUID assessmentId);
}
