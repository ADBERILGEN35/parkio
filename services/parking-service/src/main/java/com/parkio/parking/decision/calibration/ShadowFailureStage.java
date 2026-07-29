package com.parkio.parking.decision.calibration;

/** Bounded failure taxonomy for non-authoritative shadow orchestration. */
public enum ShadowFailureStage {
    EVIDENCE_COLLECTION,
    EVIDENCE_EVALUATION,
    RISK_ASSESSMENT,
    DECISION_POLICY,
    LEGACY_COMPARISON,
    OBSERVABILITY,
    CONFIGURATION,
    UNKNOWN
}
