package com.parkio.parking.calibration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CalibrationObservation(
        UUID observationId,
        CalibrationEngineType engineType,
        CalibrationPrediction prediction,
        CalibrationLabel label,
        CalibrationObservationHorizon horizon,
        String cohortKey,
        CalibrationAttributionQuality attributionQuality,
        int observationCompletenessBasisPoints,
        Instant predictedAt,
        Instant labeledAt,
        Instant createdAt) {

    public CalibrationObservation {
        Objects.requireNonNull(observationId, "observationId");
        Objects.requireNonNull(engineType, "engineType");
        Objects.requireNonNull(prediction, "prediction");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(horizon, "horizon");
        Objects.requireNonNull(cohortKey, "cohortKey");
        Objects.requireNonNull(attributionQuality, "attributionQuality");
        if (observationCompletenessBasisPoints < 0 || observationCompletenessBasisPoints > CalibrationPolicyConfig.BASIS_POINTS) {
            throw new IllegalArgumentException("observationCompletenessBasisPoints out of range");
        }
        Objects.requireNonNull(predictedAt, "predictedAt");
        Objects.requireNonNull(labeledAt, "labeledAt");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean isLabeled() {
        return label.isLabeled();
    }
}