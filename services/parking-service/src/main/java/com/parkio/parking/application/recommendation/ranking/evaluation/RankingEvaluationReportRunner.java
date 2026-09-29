package com.parkio.parking.application.recommendation.ranking.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Operator-facing offline evaluator runner. No public HTTP endpoint.
 * Reads durable privacy-safe snapshots + outcomes and prints COUNTERFACTUAL report.
 */
public final class RankingEvaluationReportRunner {

    private RankingEvaluationReportRunner() {}

    public static RankingEvaluationOfflineEvaluator.EvaluationReport run(
            RankingEvaluationStore store, Instant fromInclusive, Instant toExclusive) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        List<RankingEvaluationSnapshot> snapshots =
                store.listSnapshotsCreatedBetween(fromInclusive, toExclusive);
        List<UUID> ids = snapshots.stream().map(RankingEvaluationSnapshot::evaluationId).toList();
        List<RankingEvaluationOutcomeRecord> outcomes = store.listOutcomesForEvaluations(ids);
        return RankingEvaluationOfflineEvaluator.evaluate(snapshots, outcomes);
    }
}
