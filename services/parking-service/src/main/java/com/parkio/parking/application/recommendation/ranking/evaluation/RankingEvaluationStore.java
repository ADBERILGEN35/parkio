package com.parkio.parking.application.recommendation.ranking.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for privacy-safe ranking evaluation correlation. */
public interface RankingEvaluationStore {

    void insertSnapshot(RankingEvaluationSnapshot snapshot);

    void updateShadowOrder(
            UUID evaluationId,
            List<Integer> shadowOrderByOrdinal,
            Boolean top1Agreement,
            Integer top3Overlap,
            String shadowRankerVersion);

    Optional<RankingEvaluationSnapshot> findSnapshot(UUID evaluationId);

    /** @return true if inserted, false if duplicate (dedupe key). */
    boolean insertOutcome(RankingEvaluationOutcomeRecord outcome);

    List<RankingEvaluationSnapshot> listSnapshotsCreatedBetween(Instant fromInclusive, Instant toExclusive);

    List<RankingEvaluationOutcomeRecord> listOutcomesForEvaluations(List<UUID> evaluationIds);

    int deleteExpiredBefore(Instant cutoff, int batchSize);
}
