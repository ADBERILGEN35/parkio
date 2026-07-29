package com.parkio.parking.fraud;

import java.util.Objects;

/** Category-level fraud assessment. */
public record FraudAssessment(
        FraudAssessmentCategory category,
        FraudAssessmentLevel level,
        int contributionBasisPoints,
        FraudAttributionQuality attributionQuality,
        int evidenceCount,
        String decisiveReason) {

    public FraudAssessment {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(attributionQuality, "attributionQuality");
        Objects.requireNonNull(decisiveReason, "decisiveReason");
        if (contributionBasisPoints < 0) {
            throw new IllegalArgumentException("contribution must be non-negative");
        }
        if (evidenceCount < 0) {
            throw new IllegalArgumentException("evidenceCount must be non-negative");
        }
    }
}
