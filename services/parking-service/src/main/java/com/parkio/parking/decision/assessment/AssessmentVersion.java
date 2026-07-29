package com.parkio.parking.decision.assessment;

import java.util.Objects;

/**
 * Version identifier for an assessment model or policy (opaque string).
 */
public record AssessmentVersion(String value) {

    private static final int MAX_LENGTH = 64;

    public AssessmentVersion {
        Objects.requireNonNull(value, "value");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("assessment version must not be blank");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("assessment version must be at most " + MAX_LENGTH + " characters");
        }
        value = trimmed;
    }

    public static AssessmentVersion of(String value) {
        return new AssessmentVersion(value);
    }
}
