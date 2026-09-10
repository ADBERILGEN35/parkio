package com.parkio.parking.application.recommendation.ranking.evaluation;

/** Low-cardinality client platform tag for evaluation outcomes. */
public enum RankingEvaluationPlatform {
    WEB,
    MOBILE_V2,
    UNKNOWN;

    public static RankingEvaluationPlatform parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String normalized = raw.trim().toUpperCase().replace('-', '_');
        return switch (normalized) {
            case "WEB" -> WEB;
            case "MOBILE_V2", "MOBILE", "MOBILEV2" -> MOBILE_V2;
            default -> UNKNOWN;
        };
    }
}
