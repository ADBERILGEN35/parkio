package com.parkio.parking.presentation.dto;

/** Acknowledge outcome write without revealing stored evaluation features. */
public record RankingEvaluationOutcomeResponse(String status) {

    public static RankingEvaluationOutcomeResponse recorded() {
        return new RankingEvaluationOutcomeResponse("RECORDED");
    }

    public static RankingEvaluationOutcomeResponse duplicate() {
        return new RankingEvaluationOutcomeResponse("DUPLICATE");
    }

    public static RankingEvaluationOutcomeResponse disabled() {
        return new RankingEvaluationOutcomeResponse("DISABLED");
    }

    public static RankingEvaluationOutcomeResponse persistenceFailed() {
        return new RankingEvaluationOutcomeResponse("PERSISTENCE_FAILED");
    }
}
