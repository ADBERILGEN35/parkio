package com.parkio.parking.application.recommendation.ranking;

/** Outcome of applying ranking to a recommendation response. */
public enum RankingStatus {
    /** Ranking flag off — exact WP-SPA-05 distance order. */
    DISABLED,
    /** Deterministic v1 ranking applied. */
    APPLIED,
    /** Ranker failed; fell back to distance baseline. */
    FALLBACK
}
