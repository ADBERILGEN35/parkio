package com.parkio.parking.application.exposure;

public enum ExposureShadowFailureStage {
    CANDIDATE_MAPPING_FAILURE,
    EVALUATION_FAILURE,
    COMPARISON_FAILURE,
    REPLAY_FAILURE,
    OBSERVABILITY_FAILURE,
    POLICY_VERSION_UNSUPPORTED,
    TIME_BUDGET_EXCEEDED
}
