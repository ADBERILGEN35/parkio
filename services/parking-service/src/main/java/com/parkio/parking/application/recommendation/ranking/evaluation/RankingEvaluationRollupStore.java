package com.parkio.parking.application.recommendation.ranking.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for privacy-safe ranking evaluation rollups (WP-SPA-14D). */
public interface RankingEvaluationRollupStore {

    Optional<Instant> findCompletedThrough();

    void replaceSlice(
            Instant sliceStart,
            Instant sliceEnd,
            List<RankingEvaluationRollupRecord> rows,
            int evaluationsProcessed,
            int outcomesProcessed,
            Instant now);

    List<RankingEvaluationRollupRecord> listRollupsBetween(Instant fromInclusive, Instant toExclusive);

    int deleteRollupsOlderThan(Instant cutoffHourExclusive, int batchSize);

    /** Deletes expired raw evaluations only when created_at is before completedThrough. */
    int deleteExpiredRawBeforeWatermark(Instant expiresBefore, Instant createdBefore, int batchSize);
}
