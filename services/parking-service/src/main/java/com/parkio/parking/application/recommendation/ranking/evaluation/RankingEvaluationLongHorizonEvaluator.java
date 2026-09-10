package com.parkio.parking.application.recommendation.ranking.evaluation;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Long-horizon evaluator over privacy-safe rollups (WP-SPA-14D).
 *
 * <p>Uses sum/count weighted means. Separates CURRENT RAW WINDOW vs LONG-HORIZON AGGREGATES when
 * both are supplied. Shadow metrics use shadow-attached denominator only.
 */
public final class RankingEvaluationLongHorizonEvaluator {

    public static final String CAUSALITY_DISCLAIMER =
            RankingEvaluationOfflineEvaluator.CAUSALITY_DISCLAIMER;

    private RankingEvaluationLongHorizonEvaluator() {}

    public static LongHorizonReport evaluateRollups(
            List<RankingEvaluationRollupRecord> rollups, Instant fromInclusive, Instant toExclusive) {
        Objects.requireNonNull(rollups, "rollups");
        Map<String, OutcomeAgg> byType = new HashMap<>();
        long evaluationScaffold = 0L;
        long suppressedCells = 0L;
        for (RankingEvaluationRollupRecord row : rollups) {
            if (row.rollupHour().isBefore(fromInclusive) || !row.rollupHour().isBefore(toExclusive)) {
                continue;
            }
            if (RankingEvaluationRollupConstants.OUTCOME_NONE.equals(row.outcomeType())) {
                evaluationScaffold += row.evaluationCount();
                continue;
            }
            if (row.outcomeCount() > 0
                    && row.outcomeCount() < RankingEvaluationRollupConstants.MIN_REPORT_CELL_COUNT) {
                // Internal math still includes exact counts; report marks suppressed export cells.
                suppressedCells++;
            }
            OutcomeAgg agg = byType.computeIfAbsent(row.outcomeType(), ignored -> new OutcomeAgg());
            agg.add(row);
        }
        return new LongHorizonReport(
                fromInclusive,
                toExclusive,
                evaluationScaffold,
                toSummary(byType.get("RECOMMENDATION_SELECTED")),
                toSummary(byType.get("NAVIGATION_STARTED")),
                toSummary(byType.get("PARKING_SESSION_STARTED")),
                dataSufficiency(byType),
                suppressedCells,
                CAUSALITY_DISCLAIMER);
    }

    public static CombinedReport evaluateSeparated(
            RankingEvaluationOfflineEvaluator.EvaluationReport rawWindow,
            LongHorizonReport longHorizon) {
        return new CombinedReport(rawWindow, longHorizon, CAUSALITY_DISCLAIMER);
    }

    private static OutcomeSummary toSummary(OutcomeAgg agg) {
        if (agg == null) {
            return OutcomeSummary.empty();
        }
        return new OutcomeSummary(
                agg.outcomeCount,
                agg.shadowAttached,
                mean(agg.detRankSum, agg.outcomeCount),
                mean(agg.shadowRankSum, agg.shadowAttached),
                rate(agg.detTop1, agg.outcomeCount),
                rate(agg.detTop3, agg.outcomeCount),
                rate(agg.shadowTop1, agg.shadowAttached),
                rate(agg.shadowTop3, agg.shadowAttached),
                mean(agg.deltaSum, agg.deltaCount),
                Map.of(
                        "<=-3", agg.deltaLeM3,
                        "-2", agg.deltaM2,
                        "-1", agg.deltaM1,
                        "0", agg.delta0,
                        "+1", agg.deltaP1,
                        "+2", agg.deltaP2,
                        ">=+3", agg.deltaGeP3),
                Map.copyOf(agg.byEvidence));
    }

    private static RankingEvaluationOfflineEvaluator.DataSufficiency dataSufficiency(
            Map<String, OutcomeAgg> byType) {
        long sel = count(byType, "RECOMMENDATION_SELECTED");
        long nav = count(byType, "NAVIGATION_STARTED");
        long park = count(byType, "PARKING_SESSION_STARTED");
        long organicSel = organic(byType, "RECOMMENDATION_SELECTED");
        long organicNav = organic(byType, "NAVIGATION_STARTED");
        long organicPark = organic(byType, "PARKING_SESSION_STARTED");
        boolean enough = organicSel >= 500 && organicNav >= 100 && organicPark >= 50;
        return new RankingEvaluationOfflineEvaluator.DataSufficiency(
                enough ? "SUFFICIENT" : "INSUFFICIENT DATA",
                (int) Math.min(Integer.MAX_VALUE, sel),
                (int) Math.min(Integer.MAX_VALUE, nav),
                (int) Math.min(Integer.MAX_VALUE, park),
                500,
                100,
                50);
    }

    private static long count(Map<String, OutcomeAgg> byType, String type) {
        OutcomeAgg agg = byType.get(type);
        return agg == null ? 0L : agg.outcomeCount;
    }

    private static long organic(Map<String, OutcomeAgg> byType, String type) {
        OutcomeAgg agg = byType.get(type);
        if (agg == null) {
            return 0L;
        }
        return agg.byEvidence.getOrDefault(RankingEvaluationRollupConstants.EVIDENCE_ORGANIC, 0L);
    }

    private static Double mean(long sum, long count) {
        if (count <= 0) {
            return null;
        }
        return ((double) sum) / (double) count;
    }

    private static Double rate(long hits, long count) {
        if (count <= 0) {
            return null;
        }
        return ((double) hits) / (double) count;
    }

    private static final class OutcomeAgg {
        private long outcomeCount;
        private long shadowAttached;
        private long detRankSum;
        private long shadowRankSum;
        private long detTop1;
        private long detTop3;
        private long shadowTop1;
        private long shadowTop3;
        private long deltaSum;
        private long deltaCount;
        private long deltaLeM3;
        private long deltaM2;
        private long deltaM1;
        private long delta0;
        private long deltaP1;
        private long deltaP2;
        private long deltaGeP3;
        private final Map<String, Long> byEvidence = new HashMap<>();

        void add(RankingEvaluationRollupRecord row) {
            outcomeCount += row.outcomeCount();
            shadowAttached += row.shadowAttachedOutcomeCount();
            detRankSum += row.deterministicRankSum();
            shadowRankSum += row.shadowRankSum();
            detTop1 += row.deterministicTop1Count();
            detTop3 += row.deterministicTop3Count();
            shadowTop1 += row.shadowTop1Count();
            shadowTop3 += row.shadowTop3Count();
            deltaSum += row.rankDeltaSum();
            deltaCount += row.rankDeltaCount();
            deltaLeM3 += row.deltaLeM3();
            deltaM2 += row.deltaM2();
            deltaM1 += row.deltaM1();
            delta0 += row.delta0();
            deltaP1 += row.deltaP1();
            deltaP2 += row.deltaP2();
            deltaGeP3 += row.deltaGeP3();
            byEvidence.merge(row.evidenceSource(), row.outcomeCount(), Long::sum);
        }
    }

    public record OutcomeSummary(
            long outcomeCount,
            long shadowAttachedOutcomeCount,
            Double deterministicMeanRank,
            Double shadowCounterfactualMeanRank,
            Double deterministicTop1Rate,
            Double deterministicTop3Rate,
            Double shadowCounterfactualTop1Rate,
            Double shadowCounterfactualTop3Rate,
            Double meanRankDeltaShadowMinusDeterministic,
            Map<String, Long> rankDeltaBuckets,
            Map<String, Long> outcomesByEvidenceSource) {

        static OutcomeSummary empty() {
            return new OutcomeSummary(
                    0, 0, null, null, null, null, null, null, null, Map.of(), Map.of());
        }
    }

    public record LongHorizonReport(
            Instant fromInclusive,
            Instant toExclusive,
            long evaluationScaffoldCount,
            OutcomeSummary selection,
            OutcomeSummary navigation,
            OutcomeSummary parkingSession,
            RankingEvaluationOfflineEvaluator.DataSufficiency dataSufficiency,
            long smallCellsBelowReportThreshold,
            String causalityDisclaimer) {

        public String renderMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("# Long-horizon ranking evaluation (WP-SPA-14D)\n\n");
            sb.append(causalityDisclaimer).append("\n\n");
            sb.append("## LONG-HORIZON AGGREGATES\n");
            sb.append("- window: ")
                    .append(fromInclusive)
                    .append(" .. ")
                    .append(toExclusive)
                    .append('\n');
            sb.append("- evaluation scaffolds: ").append(evaluationScaffoldCount).append('\n');
            sb.append("- data sufficiency (organic preferred; totals shown): ")
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
                    .append(")\n");
            sb.append("- small cells below report threshold (")
                    .append(RankingEvaluationRollupConstants.MIN_REPORT_CELL_COUNT)
                    .append("): ")
                    .append(smallCellsBelowReportThreshold)
                    .append(" (counts still used in weighted aggregates; export may suppress)\n\n");
            append(sb, "Selection", selection);
            append(sb, "Navigation", navigation);
            append(sb, "Parking session", parkingSession);
            sb.append("\nShadow metrics use shadow-attached denominators only.\n");
            sb.append("Labeling: COUNTERFACTUAL_POSITIONAL — NOT CAUSAL.\n");
            return sb.toString();
        }

        private static void append(StringBuilder sb, String title, OutcomeSummary summary) {
            sb.append("### ").append(title).append('\n');
            sb.append("- outcomes: ").append(summary.outcomeCount()).append('\n');
            sb.append("- shadow-attached outcomes: ")
                    .append(summary.shadowAttachedOutcomeCount())
                    .append('\n');
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
                    .append('\n');
            sb.append("- evidence sources: ")
                    .append(summary.outcomesByEvidenceSource())
                    .append("\n\n");
        }

        private static String fmt(Double value) {
            if (value == null || !Double.isFinite(value)) {
                return "n/a";
            }
            return String.format(Locale.ROOT, "%.4f", value);
        }
    }

    public record CombinedReport(
            RankingEvaluationOfflineEvaluator.EvaluationReport currentRawWindow,
            LongHorizonReport longHorizonAggregates,
            String causalityDisclaimer) {

        public String renderMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("# Ranking evaluation report (raw + long-horizon)\n\n");
            sb.append(causalityDisclaimer).append("\n\n");
            sb.append("## CURRENT RAW WINDOW\n\n");
            if (currentRawWindow == null) {
                sb.append("_none_\n\n");
            } else {
                sb.append(currentRawWindow.renderMarkdown()).append('\n');
            }
            sb.append("## LONG-HORIZON AGGREGATES\n\n");
            if (longHorizonAggregates == null) {
                sb.append("_none_\n");
            } else {
                sb.append(longHorizonAggregates.renderMarkdown());
            }
            sb.append("\nDo not sum CURRENT RAW WINDOW with LONG-HORIZON AGGREGATES when windows overlap.\n");
            return sb.toString();
        }
    }
}
