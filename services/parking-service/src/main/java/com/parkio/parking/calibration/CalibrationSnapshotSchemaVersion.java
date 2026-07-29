package com.parkio.parking.calibration;

public enum CalibrationSnapshotSchemaVersion {
    V1("calibration-schema-v1");

    private final String value;

    CalibrationSnapshotSchemaVersion(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}