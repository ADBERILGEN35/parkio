package com.parkio.parking.application.fraud;

/** Failure stage for fraud shadow processing. */
public enum FraudShadowFailureStage {
    EVIDENCE_MAPPING_FAILURE,
    AGGREGATION_FAILURE,
    POLICY_VERSION_UNSUPPORTED,
    LEDGER_APPEND_FAILURE,
    OBSERVABILITY_FAILURE
}
