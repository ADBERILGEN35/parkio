package com.parkio.parking.calibration;

public enum CalibrationMappingVersion {
    V1("calibration-mapping-v1");

    private final String value;

    CalibrationMappingVersion(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}