package com.parkio.parking.presentation.dto;

import com.parkio.parking.application.recommendation.ranking.evaluation.RankingEvaluationOutcomeType;
import com.parkio.parking.application.recommendation.ranking.evaluation.RankingEvaluationPlatform;
import java.util.UUID;

/** Client outcome submission for privacy-safe ranking evaluation (WP-SPA-14B). */
public record RankingEvaluationOutcomeRequest(
        UUID evaluationId,
        int candidateOrdinal,
        String outcomeType,
        String platform,
        String latencyBucket) {

    public RankingEvaluationOutcomeType parsedOutcomeType() {
        return RankingEvaluationOutcomeType.parse(outcomeType);
    }

    public RankingEvaluationPlatform parsedPlatform() {
        return RankingEvaluationPlatform.parse(platform);
    }
}
