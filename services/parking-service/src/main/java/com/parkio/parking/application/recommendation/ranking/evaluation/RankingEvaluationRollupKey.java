package com.parkio.parking.application.recommendation.ranking.evaluation;

import java.time.Instant;
import java.util.Objects;

/** Unique aggregate grain for a closed UTC hour slice (no journey identity). */
public record RankingEvaluationRollupKey(
        Instant rollupHour,
        String platform,
        String inventoryComposition,
        String outcomeType,
        String evidenceSource,
        String deterministicRankingVersion,
        String shadowRankerVersion,
        String featureSchemaVersion,
        String evaluationSchemaVersion,
        String candidateCountBucket,
        String freshnessMix,
        boolean zeroAvailabilityPresent,
        boolean highCapacityPresent,
        boolean partial) {

    public RankingEvaluationRollupKey {
        Objects.requireNonNull(rollupHour, "rollupHour");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(inventoryComposition, "inventoryComposition");
        Objects.requireNonNull(outcomeType, "outcomeType");
        Objects.requireNonNull(evidenceSource, "evidenceSource");
        Objects.requireNonNull(deterministicRankingVersion, "deterministicRankingVersion");
        Objects.requireNonNull(shadowRankerVersion, "shadowRankerVersion");
        Objects.requireNonNull(featureSchemaVersion, "featureSchemaVersion");
        Objects.requireNonNull(evaluationSchemaVersion, "evaluationSchemaVersion");
        Objects.requireNonNull(candidateCountBucket, "candidateCountBucket");
        Objects.requireNonNull(freshnessMix, "freshnessMix");
    }
}
