package com.parkio.parking.application.recommendation.ranking.evaluation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/** Mutable accumulator for privacy-safe rollup cells (sum + count only). */
final class RankingEvaluationRollupAccumulator {

    private long evaluationCount;
    private long outcomeCount;
    private long shadowAttachedOutcomeCount;
    private long deterministicRankSum;
    private long shadowRankSum;
    private long deterministicTop1Count;
    private long deterministicTop3Count;
    private long shadowTop1Count;
    private long shadowTop3Count;
    private long rankDeltaSum;
    private long rankDeltaCount;
    private long deltaLeM3;
    private long deltaM2;
    private long deltaM1;
    private long delta0;
    private long deltaP1;
    private long deltaP2;
    private long deltaGeP3;
    private long zeroAvailabilitySelected;
    private long zeroAvailabilityShadowTop1;
    private long staleStaticPresent;
    private long staleStaticSelected;
    private long staleStaticShadowPromoted;

    void addEvaluationScaffold() {
        evaluationCount++;
    }

    void addOutcome(
            int deterministicRank,
            Integer shadowRank,
            boolean zeroAvailSelected,
            boolean zeroAvailShadowTop1,
            boolean stalePresent,
            boolean staleSelected,
            boolean staleShadowPromoted) {
        outcomeCount++;
        deterministicRankSum += deterministicRank;
        if (deterministicRank < 1) {
            deterministicTop1Count++;
        }
        if (deterministicRank < 3) {
            deterministicTop3Count++;
        }
        if (shadowRank != null) {
            shadowAttachedOutcomeCount++;
            shadowRankSum += shadowRank;
            if (shadowRank < 1) {
                shadowTop1Count++;
            }
            if (shadowRank < 3) {
                shadowTop3Count++;
            }
            int delta = shadowRank - deterministicRank;
            rankDeltaSum += delta;
            rankDeltaCount++;
            if (delta <= -3) {
                deltaLeM3++;
            } else if (delta == -2) {
                deltaM2++;
            } else if (delta == -1) {
                deltaM1++;
            } else if (delta == 0) {
                delta0++;
            } else if (delta == 1) {
                deltaP1++;
            } else if (delta == 2) {
                deltaP2++;
            } else {
                deltaGeP3++;
            }
        }
        if (zeroAvailSelected) {
            zeroAvailabilitySelected++;
        }
        if (zeroAvailShadowTop1) {
            zeroAvailabilityShadowTop1++;
        }
        if (stalePresent) {
            staleStaticPresent++;
        }
        if (staleSelected) {
            staleStaticSelected++;
        }
        if (staleShadowPromoted) {
            staleStaticShadowPromoted++;
        }
    }

    RankingEvaluationRollupRecord toRecord(RankingEvaluationRollupKey key) {
        LocalDate date = LocalDate.ofInstant(key.rollupHour(), ZoneOffset.UTC);
        return new RankingEvaluationRollupRecord(
                key.rollupHour(),
                date,
                key.platform(),
                key.inventoryComposition(),
                key.outcomeType(),
                key.evidenceSource(),
                key.deterministicRankingVersion(),
                key.shadowRankerVersion(),
                key.featureSchemaVersion(),
                key.evaluationSchemaVersion(),
                key.candidateCountBucket(),
                key.freshnessMix(),
                key.zeroAvailabilityPresent(),
                key.highCapacityPresent(),
                key.partial(),
                RankingEvaluationRollupConstants.EXPOSURE_POLICY,
                evaluationCount,
                outcomeCount,
                shadowAttachedOutcomeCount,
                deterministicRankSum,
                shadowRankSum,
                deterministicTop1Count,
                deterministicTop3Count,
                shadowTop1Count,
                shadowTop3Count,
                rankDeltaSum,
                rankDeltaCount,
                deltaLeM3,
                deltaM2,
                deltaM1,
                delta0,
                deltaP1,
                deltaP2,
                deltaGeP3,
                zeroAvailabilitySelected,
                zeroAvailabilityShadowTop1,
                staleStaticPresent,
                staleStaticSelected,
                staleStaticShadowPromoted);
    }

    static Map<RankingEvaluationRollupKey, RankingEvaluationRollupAccumulator> newMap() {
        return new HashMap<>();
    }

    static RankingEvaluationRollupAccumulator get(
            Map<RankingEvaluationRollupKey, RankingEvaluationRollupAccumulator> map,
            RankingEvaluationRollupKey key) {
        return map.computeIfAbsent(key, ignored -> new RankingEvaluationRollupAccumulator());
    }

    static Instant truncateToUtcHour(Instant instant) {
        long epochSecond = instant.getEpochSecond();
        long truncated = epochSecond - Math.floorMod(epochSecond, 3600L);
        return Instant.ofEpochSecond(truncated);
    }
}
