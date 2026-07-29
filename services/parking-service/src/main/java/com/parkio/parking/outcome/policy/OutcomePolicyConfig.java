package com.parkio.parking.outcome.policy;

import com.parkio.parking.outcome.OutcomeClassification;

public final class OutcomePolicyConfig {

    public static final OutcomePolicyVersion POLICY_VERSION = OutcomePolicyVersion.of("outcome-validation-v1");

    public static final int CONFIRMED_CONFIDENCE = 90;
    public static final int LIKELY_CONFIDENCE = 70;
    public static final int UNKNOWN_CONFIDENCE = 45;
    public static final int EXPIRED_NO_EVIDENCE_CONFIDENCE = 55;
    public static final int LIKELY_INCORRECT_CONFIDENCE = 65;
    public static final int CONFIRMED_INCORRECT_CONFIDENCE = 92;

    public static final int MULTIPLE_AVAILABLE_VERIFICATIONS_MIN = 2;
    public static final int FILLED_REPORTS_TERMINAL_MIN = 2;

    private static final OutcomePolicyConfig INSTANCE = new OutcomePolicyConfig();

    private OutcomePolicyConfig() {}

    public static OutcomePolicyConfig referenceV1() {
        return INSTANCE;
    }

    public OutcomePolicyVersion policyVersion() {
        return POLICY_VERSION;
    }

    public int confidenceFor(OutcomeClassification classification) {
        return switch (classification) {
            case CONFIRMED_CORRECT -> CONFIRMED_CONFIDENCE;
            case LIKELY_CORRECT -> LIKELY_CONFIDENCE;
            case UNKNOWN -> UNKNOWN_CONFIDENCE;
            case LIKELY_INCORRECT -> LIKELY_INCORRECT_CONFIDENCE;
            case CONFIRMED_INCORRECT -> CONFIRMED_INCORRECT_CONFIDENCE;
            case EXPIRED_WITHOUT_EVIDENCE -> EXPIRED_NO_EVIDENCE_CONFIDENCE;
        };
    }
}