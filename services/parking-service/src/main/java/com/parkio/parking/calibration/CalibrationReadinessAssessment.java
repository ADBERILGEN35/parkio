package com.parkio.parking.calibration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CalibrationReadinessAssessment(
        UUID assessmentId,
        CalibrationEngineType engineType,
        String policyVersion,
        UUID reportId,
        CalibrationReadinessStatus readinessStatus,
        List<CalibrationReadinessReason> reasons,
        Instant assessedAt) {

    public CalibrationReadinessAssessment {
        Objects.requireNonNull(assessmentId, "assessmentId");
        Objects.requireNonNull(engineType, "engineType");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(readinessStatus, "readinessStatus");
        Objects.requireNonNull(reasons, "reasons");
        Objects.requireNonNull(assessedAt, "assessedAt");
        reasons = List.copyOf(reasons);
    }
}