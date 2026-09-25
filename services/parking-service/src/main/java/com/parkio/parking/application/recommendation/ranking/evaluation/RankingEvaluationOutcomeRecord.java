package com.parkio.parking.application.recommendation.ranking.evaluation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Privacy-safe durable outcome linked only by evaluationId + candidateOrdinal. */
public record RankingEvaluationOutcomeRecord(
        UUID evaluationId,
        int candidateOrdinal,
        RankingEvaluationOutcomeType outcomeType,
        Instant occurredAt,
        RankingEvaluationPlatform platform,
        String latencyBucket) {

    public RankingEvaluationOutcomeRecord {
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(outcomeType, "outcomeType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(platform, "platform");
        if (candidateOrdinal < 0) {
            throw new IllegalArgumentException("candidateOrdinal must be >= 0");
        }
    }
}
