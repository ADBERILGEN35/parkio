package com.parkio.parking.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record CalibrationReplayComparison(CalibrationReport original, CalibrationReport replayed, boolean identical, List<String> mismatches) {

    public CalibrationReplayComparison {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(replayed, "replayed");
        Objects.requireNonNull(mismatches, "mismatches");
        mismatches = List.copyOf(mismatches);
    }

    public static CalibrationReplayComparison of(CalibrationReport original, CalibrationReport replayed) {
        List<String> mismatches = new ArrayList<>();
        if (original.reportStatus() != replayed.reportStatus()) {
            mismatches.add("reportStatus:" + original.reportStatus() + "->" + replayed.reportStatus());
        }
        if (original.observationCount() != replayed.observationCount()) {
            mismatches.add("observationCount:" + original.observationCount() + "->" + replayed.observationCount());
        }
        if (original.labeledCount() != replayed.labeledCount()) {
            mismatches.add("labeledCount:" + original.labeledCount() + "->" + replayed.labeledCount());
        }
        for (CalibrationMetricType type : CalibrationMetricType.values()) {
            CalibrationMetricValue originalMetric = original.metric(type).orElse(null);
            CalibrationMetricValue replayedMetric = replayed.metric(type).orElse(null);
            if (!metricsEqual(originalMetric, replayedMetric)) {
                mismatches.add("metric:" + type.name());
            }
        }
        return new CalibrationReplayComparison(original, replayed, mismatches.isEmpty(), mismatches);
    }

    private static boolean metricsEqual(CalibrationMetricValue left, CalibrationMetricValue right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.type() == right.type()
                && left.applicability() == right.applicability()
                && left.numerator() == right.numerator()
                && left.denominator() == right.denominator()
                && left.ratioBasisPoints().equals(right.ratioBasisPoints())
                && left.notes().equals(right.notes());
    }
}