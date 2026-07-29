package com.parkio.parking.calibration;

import java.util.Objects;

public final class CalibrationPolicyConfig {

    public static final String POLICY_VERSION = "calibration-policy-v1";
    public static final int BASIS_POINTS = 10_000;

    private final String policyVersion;
    private final int minimumObservations;
    private final int minimumLabeledObservations;
    private final int minimumLabelCoverageBasisPoints;
    private final int minimumReplayMatchRateBasisPoints;
    private final int maximumReplayMismatchBasisPoints;
    private final int minimumPrecisionBasisPoints;
    private final int minimumRecallBasisPoints;
    private final int bandCalibrationMinimumObservations;

    public CalibrationPolicyConfig(
            String policyVersion,
            int minimumObservations,
            int minimumLabeledObservations,
            int minimumLabelCoverageBasisPoints,
            int minimumReplayMatchRateBasisPoints,
            int maximumReplayMismatchBasisPoints,
            int minimumPrecisionBasisPoints,
            int minimumRecallBasisPoints,
            int bandCalibrationMinimumObservations) {
        this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
        this.minimumObservations = requirePositive(minimumObservations, "minimumObservations");
        this.minimumLabeledObservations = requirePositive(minimumLabeledObservations, "minimumLabeledObservations");
        this.minimumLabelCoverageBasisPoints =
                requireBounded(minimumLabelCoverageBasisPoints, "minimumLabelCoverageBasisPoints");
        this.minimumReplayMatchRateBasisPoints =
                requireBounded(minimumReplayMatchRateBasisPoints, "minimumReplayMatchRateBasisPoints");
        this.maximumReplayMismatchBasisPoints =
                requireBounded(maximumReplayMismatchBasisPoints, "maximumReplayMismatchBasisPoints");
        this.minimumPrecisionBasisPoints = requireBounded(minimumPrecisionBasisPoints, "minimumPrecisionBasisPoints");
        this.minimumRecallBasisPoints = requireBounded(minimumRecallBasisPoints, "minimumRecallBasisPoints");
        this.bandCalibrationMinimumObservations =
                requirePositive(bandCalibrationMinimumObservations, "bandCalibrationMinimumObservations");
        validateMonotonicThresholds();
    }

    public static CalibrationPolicyConfig referenceV1() {
        return new CalibrationPolicyConfig(
                POLICY_VERSION,
                10,
                5,
                5_000,
                9_900,
                100,
                7_000,
                7_000,
                5);
    }

    public static CalibrationPolicyConfig requireSupported(String policyVersion) {
        if (!POLICY_VERSION.equals(policyVersion)) {
            throw new UnsupportedCalibrationPolicyVersionException(policyVersion);
        }
        return referenceV1();
    }

    public String policyVersion() {
        return policyVersion;
    }

    public int minimumObservations() {
        return minimumObservations;
    }

    public int minimumLabeledObservations() {
        return minimumLabeledObservations;
    }

    public int minimumLabelCoverageBasisPoints() {
        return minimumLabelCoverageBasisPoints;
    }

    public int minimumReplayMatchRateBasisPoints() {
        return minimumReplayMatchRateBasisPoints;
    }

    public int maximumReplayMismatchBasisPoints() {
        return maximumReplayMismatchBasisPoints;
    }

    public int minimumPrecisionBasisPoints() {
        return minimumPrecisionBasisPoints;
    }

    public int minimumRecallBasisPoints() {
        return minimumRecallBasisPoints;
    }

    public int bandCalibrationMinimumObservations() {
        return bandCalibrationMinimumObservations;
    }

    private void validateMonotonicThresholds() {
        if (minimumLabeledObservations > minimumObservations) {
            throw new IllegalArgumentException("minimumLabeledObservations must not exceed minimumObservations");
        }
        if (bandCalibrationMinimumObservations > minimumObservations) {
            throw new IllegalArgumentException("bandCalibrationMinimumObservations must not exceed minimumObservations");
        }
        if (minimumReplayMatchRateBasisPoints + maximumReplayMismatchBasisPoints > BASIS_POINTS) {
            throw new IllegalArgumentException("replay match and mismatch thresholds must be monotonic");
        }
        if (minimumPrecisionBasisPoints > BASIS_POINTS || minimumRecallBasisPoints > BASIS_POINTS) {
            throw new IllegalArgumentException("precision and recall thresholds must be bounded");
        }
        if (minimumLabelCoverageBasisPoints > BASIS_POINTS) {
            throw new IllegalArgumentException("minimumLabelCoverageBasisPoints out of range");
        }
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int requireBounded(int value, String name) {
        if (value < 0 || value > BASIS_POINTS) {
            throw new IllegalArgumentException(name + " must be between 0 and " + BASIS_POINTS);
        }
        return value;
    }
}