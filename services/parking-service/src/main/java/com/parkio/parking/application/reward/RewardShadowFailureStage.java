package com.parkio.parking.application.reward;

/** Bounded failure taxonomy for reward shadow processing. */
public enum RewardShadowFailureStage {
    CONTRIBUTION_MAPPING_FAILURE,
    LEDGER_APPEND_FAILURE,
    OBSERVABILITY_FAILURE,
    POLICY_VERSION_UNSUPPORTED,
    UNKNOWN
}
