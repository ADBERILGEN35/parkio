package com.parkio.parking.calibration;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public record CalibrationMetricValue(
        CalibrationMetricType type,
        CalibrationMetricApplicability applicability,
        long numerator,
        long denominator,
        OptionalInt ratioBasisPoints,
        Optional<String> notes) {

    public CalibrationMetricValue {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(applicability, "applicability");
        Objects.requireNonNull(ratioBasisPoints, "ratioBasisPoints");
        Objects.requireNonNull(notes, "notes");
        if (numerator < 0 || denominator < 0) {
            throw new IllegalArgumentException("numerator and denominator must be non-negative");
        }
    }

    public static CalibrationMetricValue notApplicable(CalibrationMetricType type, String reason) {
        return new CalibrationMetricValue(
                type,
                CalibrationMetricApplicability.NOT_APPLICABLE,
                0L,
                0L,
                OptionalInt.empty(),
                Optional.of(reason));
    }

    public static CalibrationMetricValue insufficientData(CalibrationMetricType type, long numerator, long denominator) {
        return new CalibrationMetricValue(
                type,
                CalibrationMetricApplicability.INSUFFICIENT_DATA,
                numerator,
                denominator,
                OptionalInt.empty(),
                Optional.empty());
    }

    public static CalibrationMetricValue ratio(
            CalibrationMetricType type, long numerator, long denominator, Optional<String> notes) {
        OptionalInt basisPoints = denominator == 0
                ? OptionalInt.empty()
                : OptionalInt.of(safeRatioBasisPoints(numerator, denominator));
        return new CalibrationMetricValue(
                type,
                CalibrationMetricApplicability.APPLICABLE,
                numerator,
                denominator,
                basisPoints,
                notes);
    }

    private static int safeRatioBasisPoints(long numerator, long denominator) {
        long scaled = (numerator * CalibrationPolicyConfig.BASIS_POINTS) / denominator;
        if (scaled > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) scaled;
    }
}