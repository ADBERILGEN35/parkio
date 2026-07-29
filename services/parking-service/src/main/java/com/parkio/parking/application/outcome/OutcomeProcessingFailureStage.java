package com.parkio.parking.application.outcome;

/** Bounded failure taxonomy for outcome operationalization. */
public enum OutcomeProcessingFailureStage {
    EVIDENCE_READ_FAILURE,
    POLICY_VERSION_UNSUPPORTED,
    EVALUATION_FAILURE,
    HISTORY_APPEND_FAILURE,
    DUPLICATE_ALREADY_RECORDED,
    TRIGGER_INELIGIBLE,
    OBSERVABILITY_FAILURE,
    UNKNOWN
}