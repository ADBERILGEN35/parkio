package com.parkio.parking.application.recommendation.ranking.shadow;

/** Outcome of a shadow ranking evaluation attempt. */
public enum ShadowRankingStatus {
    DISABLED,
    NOT_SAMPLED,
    QUEUED,
    SUCCESS,
    TIMEOUT,
    PROVIDER_ERROR,
    INVALID_OUTPUT,
    CIRCUIT_OPEN,
    BUDGET_SKIPPED
}
