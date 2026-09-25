package com.parkio.parking.application.recommendation.ranking;

/**
 * Immutable per-factor scores in [0,1]. Missing data yields 0 for that factor
 * (never NaN / negative).
 */
public record CandidateScoreBreakdown(
        double distance,
        double freshness,
        double capacity,
        double confidence,
        double favourite) {

    public CandidateScoreBreakdown {
        distance = clamp(distance);
        freshness = clamp(freshness);
        capacity = clamp(capacity);
        confidence = clamp(confidence);
        favourite = clamp(favourite);
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
