package com.parkio.parking.calibration;

import java.util.List;
import java.util.Objects;

public record CalibrationSnapshot(
        CalibrationSnapshotSchemaVersion schemaVersion,
        CalibrationMappingVersion mappingVersion,
        CalibrationPolicyConfig policyConfig,
        CalibrationReport report,
        List<CalibrationObservation> observations) {

    public CalibrationSnapshot {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(mappingVersion, "mappingVersion");
        Objects.requireNonNull(policyConfig, "policyConfig");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(observations, "observations");
        observations = List.copyOf(observations);
    }
}