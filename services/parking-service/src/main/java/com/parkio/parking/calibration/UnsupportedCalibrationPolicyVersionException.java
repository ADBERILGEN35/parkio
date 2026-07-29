package com.parkio.parking.calibration;

public final class UnsupportedCalibrationPolicyVersionException extends RuntimeException {

    private final String policyVersion;

    public UnsupportedCalibrationPolicyVersionException(String policyVersion) {
        super("Unsupported calibration policy version: " + policyVersion);
        this.policyVersion = policyVersion;
    }

    public String policyVersion() {
        return policyVersion;
    }
}