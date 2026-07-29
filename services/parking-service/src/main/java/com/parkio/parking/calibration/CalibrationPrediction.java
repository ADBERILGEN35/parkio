package com.parkio.parking.calibration;

import java.util.Objects;
import java.util.UUID;

public record CalibrationPrediction(
        CalibrationEngineType engineType,
        String policyVersion,
        String schemaVersion,
        String mappingVersion,
        String aggregationVersion,
        String predictedBand,
        String predictedCategory,
        UUID sourceEvaluationId) {

    public CalibrationPrediction {
        Objects.requireNonNull(engineType, "engineType");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(mappingVersion, "mappingVersion");
        Objects.requireNonNull(aggregationVersion, "aggregationVersion");
        Objects.requireNonNull(predictedBand, "predictedBand");
        Objects.requireNonNull(predictedCategory, "predictedCategory");
        Objects.requireNonNull(sourceEvaluationId, "sourceEvaluationId");
    }
}