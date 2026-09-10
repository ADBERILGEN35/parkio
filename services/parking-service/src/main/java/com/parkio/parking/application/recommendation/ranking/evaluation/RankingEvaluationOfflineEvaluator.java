package com.parkio.parking.application.recommendation.ranking.evaluation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Offline observational / counterfactual ranking evaluator (WP-SPA-14B).
 *
 * <p>Users were exposed only to deterministic order. Shadow ranks are
 * COUNTERFACTUAL_POSITIONAL — not causal lift.
 */
public final class RankingEvaluationOfflineEvaluator {

    public static final String CAUSALITY_DISCLAIMER =
            "OBSERVATIONAL / COUNTERFACTUAL — NOT CAUSAL. "
                    + "The user was exposed only to deterministic order. "
                    + "Shadow candidate rank is COUNTERFACTUAL_POSITIONAL.";

    private RankingEvaluationOfflineEvaluator() {}

    public static EvaluationReport evaluate(
            List<RankingEvaluationSnapshot> snapshots,
            List<RankingEvaluationOutcomeRecord> outcomes) {
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(outcomes, "outcomes");

        Map<UUID, RankingEvaluationSnapshot> byId = new HashMap<>();
        for (RankingEvaluationSnapshot snapshot : snapshots) {
            byId.put(snapshot.evaluationId(), snapshot);
        }

        Map<RankingEvaluationOutcomeType, RankStats> stats = new EnumMap<>(RankingEvaluationOutcomeType.class);
        for (RankingEvaluationOutcomeType type : RankingEvaluationOutcomeType.values()) {
            stats.put(type, new RankStats());
        }

        int linkedOutcomes = 0;
        for (RankingEvaluationOutcomeRecord outcome : outcomes) {
            RankingEvaluationSnapshot snapshot = byId.get(outcome.evaluationId());
            if (snapshot == null) {
                continue;
            }
            linkedOutcomes++;
            int deterministicRank = outcome.candidateOrdinal(); // public order == deterministic ordinal
            Integer shadowRank = shadowRankOf(snapshot, outcome.candidateOrdinal());
            RankStats bucket = stats.get(outcome.outcomeType());
            bucket.add(deterministicRank, shadowRank);
        }

        return new EvaluationReport(
                snapshots.size(),
                outcomes.size(),
                linkedOutcomes,
                toOutcomeSummary(stats.get(RankingEvaluationOutcomeType.RECOMMENDATION_SELECTED)),
                toOutcomeSummary(stats.get(RankingEvaluationOutcomeType.NAVIGATION_STARTED)),
                toOutcomeSummary(stats.get(RankingEvaluationOutcomeType.PARKING_SESSION_STARTED)),
                dataSufficiency(stats),
                CAUSALITY_DISCLAIMER);
    }

    static Integer shadowRankOf(RankingEvaluationSnapshot snapshot, int candidateOrdinal) {
        List<Integer> shadowOrder = snapshot.shadowOrderByOrdinal();
        if (shadowOrder == null || shadowOrder.isEmpty()) {
            return null;
        }
        for (int i = 0; i < shadowOrder.size(); i++) {
            if (shadowOrder.get(i) == candidateOrdinal) {
                return i;
            }
        }
        return null;
    }

    private static OutcomeSummary toOutcomeSummary(RankStats stats) {
        return new OutcomeSummary(
                stats.count,
                stats.mean(stats.deterministicRanks),
                stats.mean(stats.shadowRanks),
                stats.topKRate(stats.deterministicRanks, 1),
                stats.topKRate(stats.deterministicRanks, 3),
                stats.topKRate(stats.shadowRanks, 1),
                stats.topKRate(stats.shadowRanks, 3),
                stats.meanDelta(),
                stats.mrr(stats.deterministicRanks),
                stats.mrr(stats.shadowRanks));
    }

    private static DataSufficiency dataSufficiency(Map<RankingEvaluationOutcomeType, RankStats> stats) {
        int selections = stats.get(RankingEvaluationOutcomeType.RECOMMENDATION_SELECTED).count;
        int navigations = stats.get(RankingEvaluationOutcomeType.NAVIGATION_STARTED).count;
        int parkings = stats.get(RankingEvaluationOutcomeType.PARKING_SESSION_STARTED).count;
        boolean enough =
                selections >= 500 && navigations >= 100 && parkings >= 50;
        return new DataSufficiency(
                enough ? "SUFFICIENT" : "INSUFFICIENT DATA",
                selections,
                navigations,
                parkings,
                500,
                100,
                50);
    }

    private static final class RankStats {
        private int count;
        private final List<Integer> deterministicRanks = new ArrayList<>();
        private final List<Integer> shadowRanks = new ArrayList<>();
        private final List<Integer> deltas = new ArrayList<>();

        void add(int deterministicRank, Integer shadowRank) {
            count++;
            deterministicRanks.add(deterministicRank);
            if (shadowRank != null) {
                shadowRanks.add(shadowRank);
                deltas.add(shadowRank - deterministicRank);
            }
        }

        Double mean(List<Integer> values) {
            if (values.isEmpty()) {
                return null;
            }
            double sum = 0.0;
            for (int value : values) {
                sum += value;
            }
            return sum / values.size();
        }

        Double topKRate(List<Integer> ranks, int k) {
            if (ranks.isEmpty()) {
                return null;
            }
            int hits = 0;
            for (int rank : ranks) {
                if (rank < k) {
                    hits++;
                }
            }
            return (double) hits / ranks.size();
        }

        Double meanDelta() {
            return mean(deltas);
        }

        Double mrr(List<Integer> ranks) {
            if (ranks.isEmpty()) {
                return null;
            }
            double sum = 0.0;
            for (int rank : ranks) {
                sum += 1.0 / (rank + 1.0);
            }
            return sum / ranks.size();
        }
    }

    public record OutcomeSummary(
            int count,
            Double deterministicMeanRank,
            Double shadowCounterfactualMeanRank,
            Double deterministicTop1Rate,
            Double deterministicTop3Rate,
            Double shadowCounterfactualTop1Rate,
            Double shadowCounterfactualTop3Rate,
            Double meanRankDeltaShadowMinusDeterministic,
            Double deterministicMrr,
            Double shadowCounterfactualMrr) {}

    public record DataSufficiency(
            String status,
            int selectionCount,
            int navigationCount,
            int parkingSessionCount,
            int selectionGate,
            int navigationGate,
            int parkingGate) {}

    public record EvaluationReport(
            int evaluationCount,
            int outcomeCount,
            int linkedOutcomeCount,
            OutcomeSummary selection,
            OutcomeSummary navigation,
            OutcomeSummary parkingSession,
            DataSufficiency dataSufficiency,
            String causalityDisclaimer) {

        public String renderMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("# Ranking evaluation report (WP-SPA-14B)\n\n");
            sb.append(causalityDisclaimer).append("\n\n");
            sb.append("- Evaluations: ").append(evaluationCount).append('\n');
            sb.append("- Outcomes: ").append(outcomeCount).append('\n');
            sb.append("- Linked outcomes: ").append(linkedOutcomeCount).append('\n');
            sb.append("- Data sufficiency: ")
                    .append(dataSufficiency.status())
                    .append(" (sel=")
                    .append(dataSufficiency.selectionCount())
                    .append('/')
                    .append(dataSufficiency.selectionGate())
                    .append(", nav=")
                    .append(dataSufficiency.navigationCount())
                    .append('/')
                    .append(dataSufficiency.navigationGate())
                    .append(", park=")
                    .append(dataSufficiency.parkingSessionCount())
                    .append('/')
                    .append(dataSufficiency.parkingGate())
                    .append(")\n\n");
            appendOutcome(sb, "Selection", selection);
            appendOutcome(sb, "Navigation", navigation);
            appendOutcome(sb, "Parking session", parkingSession);
            sb.append("\nLabeling: shadow metrics are COUNTERFACTUAL_POSITIONAL.\n");
            return sb.toString();
        }

        private static void appendOutcome(StringBuilder sb, String title, OutcomeSummary summary) {
            sb.append("## ").append(title).append("\n");
            sb.append("- count: ").append(summary.count()).append('\n');
            sb.append("- deterministic mean rank: ")
                    .append(fmt(summary.deterministicMeanRank()))
                    .append('\n');
            sb.append("- shadow counterfactual mean rank: ")
                    .append(fmt(summary.shadowCounterfactualMeanRank()))
                    .append('\n');
            sb.append("- deterministic top1/top3: ")
                    .append(fmt(summary.deterministicTop1Rate()))
                    .append(" / ")
                    .append(fmt(summary.deterministicTop3Rate()))
                    .append('\n');
            sb.append("- shadow counterfactual top1/top3: ")
                    .append(fmt(summary.shadowCounterfactualTop1Rate()))
                    .append(" / ")
                    .append(fmt(summary.shadowCounterfactualTop3Rate()))
                    .append('\n');
            sb.append("- mean delta (shadow - deterministic): ")
                    .append(fmt(summary.meanRankDeltaShadowMinusDeterministic()))
                    .append("\n\n");
        }

        private static String fmt(Double value) {
            if (value == null || !Double.isFinite(value)) {
                return "n/a";
            }
            return String.format(Locale.ROOT, "%.4f", value);
        }
    }
}
