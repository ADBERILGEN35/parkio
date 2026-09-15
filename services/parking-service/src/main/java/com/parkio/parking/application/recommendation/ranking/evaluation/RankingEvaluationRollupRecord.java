package com.parkio.parking.application.recommendation.ranking.evaluation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Privacy-safe long-horizon aggregate row (WP-SPA-14D).
 *
 * <p>Grain includes closed UTC {@code rollupHour} for idempotent replace; reports may group by
 * {@code rollupDate}. No evaluation/candidate/user identity.
 */
public record RankingEvaluationRollupRecord(
        Instant rollupHour,
        LocalDate rollupDate,
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
        boolean partial,
        String exposurePolicy,
        long evaluationCount,
        long outcomeCount,
        long shadowAttachedOutcomeCount,
        long deterministicRankSum,
        long shadowRankSum,
        long deterministicTop1Count,
        long deterministicTop3Count,
        long shadowTop1Count,
        long shadowTop3Count,
        long rankDeltaSum,
        long rankDeltaCount,
        long deltaLeM3,
        long deltaM2,
        long deltaM1,
        long delta0,
        long deltaP1,
        long deltaP2,
        long deltaGeP3,
        long zeroAvailabilitySelected,
        long zeroAvailabilityShadowTop1,
        long staleStaticPresent,
        long staleStaticSelected,
        long staleStaticShadowPromoted) {

    public RankingEvaluationRollupRecord {
        Objects.requireNonNull(rollupHour, "rollupHour");
        Objects.requireNonNull(rollupDate, "rollupDate");
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
        Objects.requireNonNull(exposurePolicy, "exposurePolicy");
    }

    public RankingEvaluationRollupKey key() {
        return new RankingEvaluationRollupKey(
                rollupHour,
                platform,
                inventoryComposition,
                outcomeType,
                evidenceSource,
                deterministicRankingVersion,
                shadowRankerVersion,
                featureSchemaVersion,
                evaluationSchemaVersion,
                candidateCountBucket,
                freshnessMix,
                zeroAvailabilityPresent,
                highCapacityPresent,
                partial);
    }
}
