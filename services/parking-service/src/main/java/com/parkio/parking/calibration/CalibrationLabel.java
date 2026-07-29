package com.parkio.parking.calibration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CalibrationLabel(
        CalibrationLabelCategory labelCategory,
        CalibrationLabelSource labelSource,
        CalibrationLabelQuality labelQuality,
        CalibrationLabelFinality labelFinality,
        UUID sourceRecordId,
        Instant labeledAt) {

    public CalibrationLabel {
        Objects.requireNonNull(labelCategory, "labelCategory");
        Objects.requireNonNull(labelSource, "labelSource");
        Objects.requireNonNull(labelQuality, "labelQuality");
        Objects.requireNonNull(labelFinality, "labelFinality");
        Objects.requireNonNull(sourceRecordId, "sourceRecordId");
        Objects.requireNonNull(labeledAt, "labeledAt");
    }

    public boolean isLabeled() {
        return labelSource != CalibrationLabelSource.NOT_AVAILABLE
                && labelCategory != CalibrationLabelCategory.NOT_APPLICABLE;
    }

    public boolean countsTowardClassificationMetrics() {
        return labelCategory == CalibrationLabelCategory.POSITIVE
                || labelCategory == CalibrationLabelCategory.NEGATIVE;
    }
}