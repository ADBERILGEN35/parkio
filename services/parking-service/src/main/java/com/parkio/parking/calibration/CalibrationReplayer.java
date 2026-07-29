package com.parkio.parking.calibration;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public final class CalibrationReplayer {

    private CalibrationReplayer() {}

    public static CalibrationReport replayReport(CalibrationSnapshot snapshot, Instant replayedAt) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(replayedAt, "replayedAt");
        if (snapshot.schemaVersion() != CalibrationSnapshotSchemaVersion.V1) {
            throw new IllegalArgumentException("Unsupported snapshot schema: " + snapshot.schemaVersion());
        }
        return CalibrationReportGenerator.regenerate(snapshot, replayedAt);
    }

    public static CalibrationReplayComparison replayAndCompare(CalibrationSnapshot snapshot, Instant replayedAt) {
        CalibrationReport replayed = replayReport(snapshot, replayedAt);
        return CalibrationReplayComparison.of(snapshot.report(), replayed);
    }

    public static CalibrationComparisonResult compareReports(
            CalibrationReport baseline, CalibrationReport candidate, CalibrationPolicyConfig policyConfig) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(policyConfig, "policyConfig");
        if (baseline.engineType() != candidate.engineType()) {
            return CalibrationComparisonResult.NOT_APPLICABLE;
        }

        int baselinePrecision = basisPointsOrDefault(baseline, CalibrationMetricType.PRECISION);
        int candidatePrecision = basisPointsOrDefault(candidate, CalibrationMetricType.PRECISION);
        if (baselinePrecision < 0 || candidatePrecision < 0) {
            return CalibrationComparisonResult.INCONCLUSIVE;
        }

        if (candidatePrecision > baselinePrecision) {
            return CalibrationComparisonResult.IMPROVED;
        }
        if (candidatePrecision < baselinePrecision) {
            return CalibrationComparisonResult.REGRESSED;
        }
        return CalibrationComparisonResult.INCONCLUSIVE;
    }

    private static int basisPointsOrDefault(CalibrationReport report, CalibrationMetricType type) {
        return report.metric(type)
                .flatMap(metric -> {
                    OptionalInt basisPoints = metric.ratioBasisPoints();
                    return basisPoints.isPresent()
                            ? Optional.of(basisPoints.getAsInt())
                            : Optional.empty();
                })
                .orElse(-1);
    }
}