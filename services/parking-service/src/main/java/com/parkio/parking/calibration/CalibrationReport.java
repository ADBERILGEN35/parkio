package com.parkio.parking.calibration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CalibrationReport(
        UUID reportId,
        CalibrationEngineType engineType,
        Optional<String> baselinePolicyVersion,
        Optional<String> candidatePolicyVersion,
        String calibrationPolicyVersion,
        CalibrationWindow window,
        CalibrationCohortKey cohortKey,
        long observationCount,
        long labeledCount,
        List<CalibrationMetricValue> metrics,
        CalibrationReportStatus reportStatus,
        Instant sourceWatermark,
        Instant generatedAt) {

    public CalibrationReport {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(engineType, "engineType");
        Objects.requireNonNull(baselinePolicyVersion, "baselinePolicyVersion");
        Objects.requireNonNull(candidatePolicyVersion, "candidatePolicyVersion");
        Objects.requireNonNull(calibrationPolicyVersion, "calibrationPolicyVersion");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(cohortKey, "cohortKey");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(reportStatus, "reportStatus");
        Objects.requireNonNull(sourceWatermark, "sourceWatermark");
        Objects.requireNonNull(generatedAt, "generatedAt");
        metrics = List.copyOf(metrics);
        if (observationCount < 0 || labeledCount < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
        if (labeledCount > observationCount) {
            throw new IllegalArgumentException("labeledCount must not exceed observationCount");
        }
    }

    public Optional<CalibrationMetricValue> metric(CalibrationMetricType type) {
        return metrics.stream().filter(metric -> metric.type() == type).findFirst();
    }
}