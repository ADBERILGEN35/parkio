package com.parkio.parking.application.trust;

/** Bounded trust shadow processing failures. */
public enum TrustShadowFailureStage {
    OUTCOME_READ_FAILURE,
    EVIDENCE_MAPPING_FAILURE,
    LEDGER_APPEND_FAILURE,
    SNAPSHOT_WRITE_FAILURE,
    SNAPSHOT_CONFLICT,
    REPLAY_FAILURE,
    OBSERVABILITY_FAILURE,
    UNKNOWN
}

