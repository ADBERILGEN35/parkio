package com.parkio.parking.application.calibration;

import com.parkio.parking.calibration.CalibrationEngineType;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Result of processing one continuous-calibration batch. */
public record CalibrationProcessingResult(
        CalibrationEngineType engineType,
        Status status,
        int candidateCount,
        int appendedCount,
        int duplicateCount,
        int failedCount,
        Optional<CalibrationFailureStage> failureStage,
        Optional<UUID> reportId) {

    public CalibrationProcessingResult {
        Objects.requireNonNull(engineType, "engineType");
        Objects.requireNonNull(status, "status");
        if (candidateCount < 0 || appendedCount < 0 || duplicateCount < 0 || failedCount < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
        failureStage = failureStage == null ? Optional.empty() : failureStage;
        reportId = reportId == null ? Optional.empty() : reportId;
    }

    public static CalibrationProcessingResult appended(
            CalibrationEngineType engineType, int candidateCount, int appendedCount, int duplicateCount, int failedCount) {
        return new CalibrationProcessingResult(
                engineType, Status.APPENDED, candidateCount, appendedCount, duplicateCount, failedCount, Optional.empty(), Optional.empty());
    }

    public static CalibrationProcessingResult duplicate(
            CalibrationEngineType engineType, int candidateCount, int duplicateCount) {
        return new CalibrationProcessingResult(
                engineType,
                Status.DUPLICATE,
                candidateCount,
                0,
                duplicateCount,
                0,
                Optional.empty(),
                Optional.empty());
    }

    public static CalibrationProcessingResult reportGenerated(
            CalibrationEngineType engineType,
            int candidateCount,
            int appendedCount,
            int duplicateCount,
            int failedCount,
            UUID reportId) {
        return new CalibrationProcessingResult(
                engineType,
                Status.REPORT_GENERATED,
                candidateCount,
                appendedCount,
                duplicateCount,
                failedCount,
                Optional.empty(),
                Optional.of(reportId));
    }

    public static CalibrationProcessingResult failed(
            CalibrationEngineType engineType, int candidateCount, CalibrationFailureStage stage) {
        return new CalibrationProcessingResult(
                engineType, Status.FAILED, candidateCount, 0, 0, candidateCount, Optional.of(stage), Optional.empty());
    }

    public enum Status {
        APPENDED,
        DUPLICATE,
        FAILED,
        REPORT_GENERATED
    }
}
