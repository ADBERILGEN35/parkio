package com.parkio.parking.calibration;

public enum CalibrationReadinessReason {
    MIN_OBSERVATIONS("min_observations"),
    MIN_LABELED("min_labeled"),
    LABEL_COVERAGE_LOW("label_coverage_low"),
    REPLAY_MISMATCH("replay_mismatch"),
    OPERATIONAL_VERIFICATION_MISSING("operational_verification_missing"),
    CALIBRATION_INCOMPLETE("calibration_incomplete"),
    REGRESSION_DETECTED("regression_detected"),
    FAIRNESS_REVIEW_REQUIRED("fairness_review_required"),
    PRIVACY_REVIEW_REQUIRED("privacy_review_required"),
    REPORT_NOT_GENERATED("report_not_generated"),
    REPORT_INSUFFICIENT_DATA("report_insufficient_data"),
    METRIC_INSUFFICIENT_DATA("metric_insufficient_data"),
    POLICY_VERSION_UNSUPPORTED("policy_version_unsupported"),
    INCONCLUSIVE_EVIDENCE("inconclusive_evidence"),
    STABLE_BASELINE("stable_baseline"),
    ALL_CHECKS_PASSED("all_checks_passed");

    private final String code;

    CalibrationReadinessReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}