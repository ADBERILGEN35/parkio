package com.parkio.parking.calibration;

import java.util.Objects;
import java.util.Optional;

public record CalibrationCohortKey(
        CalibrationEngineType engineType,
        String policyVersion,
        String predictionBand,
        Optional<CalibrationLabelCategory> labelCategory,
        CalibrationObservationHorizon horizon) {

    public CalibrationCohortKey {
        Objects.requireNonNull(engineType, "engineType");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(predictionBand, "predictionBand");
        Objects.requireNonNull(labelCategory, "labelCategory");
        Objects.requireNonNull(horizon, "horizon");
    }

    public String canonicalKey() {
        String labelPart = labelCategory.map(Enum::name).orElse("*");
        return engineType.name()
                + "|"
                + policyVersion
                + "|"
                + predictionBand
                + "|"
                + labelPart
                + "|"
                + horizon.name();
    }
}