package com.parkio.parking.exposure;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record ExposureEvaluation(
        ExposureEvidence evidence,
        ExposureEligibility eligibility,
        ExposureEligibilityReason primaryEligibilityReason,
        ExposureDisposition disposition,
        ExposureScore score,
        String decisiveReason,
        Set<ExposureEligibilityReason> eligibilityReasons,
        String policyVersion,
        Instant evaluatedAt) {

    public ExposureEvaluation {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(primaryEligibilityReason, "primaryEligibilityReason");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(decisiveReason, "decisiveReason");
        Objects.requireNonNull(eligibilityReasons, "eligibilityReasons");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        eligibilityReasons = Set.copyOf(eligibilityReasons);
    }

    public boolean eligible() {
        return eligibility == ExposureEligibility.ELIGIBLE;
    }
}
