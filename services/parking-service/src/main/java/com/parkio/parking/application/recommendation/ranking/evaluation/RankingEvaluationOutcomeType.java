package com.parkio.parking.application.recommendation.ranking.evaluation;

/** Explicit user-action outcomes for privacy-safe ranking evaluation (WP-SPA-14B). */
public enum RankingEvaluationOutcomeType {
    RECOMMENDATION_SELECTED,
    NAVIGATION_STARTED,
    PARKING_SESSION_STARTED,
    RETURN_TO_CAR_STARTED,
    PARKING_SESSION_ENDED;

    public static RankingEvaluationOutcomeType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("outcomeType required");
        }
        return RankingEvaluationOutcomeType.valueOf(raw.trim());
    }
}
