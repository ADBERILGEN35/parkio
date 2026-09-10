package com.parkio.parking.application.recommendation.ranking.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Privacy-safe durable evaluation snapshot (no identity / location / candidate IDs). */
public record RankingEvaluationSnapshot(
        UUID evaluationId,
        Instant createdAt,
        Instant expiresAt,
        String rankingVersion,
        String rankingStatus,
        String shadowRankerVersion,
        String featureSchemaVersion,
        int candidateCount,
        boolean inventoryPartial,
        String inventoryComposition,
        List<Integer> deterministicOrderByOrdinal,
        List<Integer> shadowOrderByOrdinal,
        String featuresJson,
        Boolean top1Agreement,
        Integer top3Overlap) {

    public RankingEvaluationSnapshot {
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(rankingVersion, "rankingVersion");
        Objects.requireNonNull(rankingStatus, "rankingStatus");
        Objects.requireNonNull(featureSchemaVersion, "featureSchemaVersion");
        Objects.requireNonNull(inventoryComposition, "inventoryComposition");
        Objects.requireNonNull(deterministicOrderByOrdinal, "deterministicOrderByOrdinal");
        Objects.requireNonNull(featuresJson, "featuresJson");
        deterministicOrderByOrdinal = List.copyOf(deterministicOrderByOrdinal);
        shadowOrderByOrdinal =
                shadowOrderByOrdinal == null ? null : List.copyOf(shadowOrderByOrdinal);
        if (candidateCount < 0) {
            throw new IllegalArgumentException("candidateCount must be >= 0");
        }
    }
}
