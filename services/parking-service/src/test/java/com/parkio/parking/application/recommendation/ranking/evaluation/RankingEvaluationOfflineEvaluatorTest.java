package com.parkio.parking.application.recommendation.ranking.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankingEvaluationOfflineEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    @Test
    void selectionUsesDeterministicVsShadowCounterfactualRanks() {
        UUID evaluationId = UUID.randomUUID();
        // Public/deterministic order: ordinal 0 at rank 0, ordinal 1 at rank 1.
        // Shadow order reverses: ordinal 1 at counterfactual rank 0.
        RankingEvaluationSnapshot snapshot = snapshot(evaluationId, List.of(0, 1), List.of(1, 0));
        RankingEvaluationOutcomeRecord selected = outcome(
                evaluationId, 1, RankingEvaluationOutcomeType.RECOMMENDATION_SELECTED);

        RankingEvaluationOfflineEvaluator.EvaluationReport report =
                RankingEvaluationOfflineEvaluator.evaluate(List.of(snapshot), List.of(selected));

        assertEquals(1, report.selection().count());
        assertEquals(1.0, report.selection().deterministicMeanRank());
        assertEquals(0.0, report.selection().shadowCounterfactualMeanRank());
        assertEquals(0.0, report.selection().deterministicTop1Rate());
        assertEquals(1.0, report.selection().shadowCounterfactualTop1Rate());
        assertEquals(-1.0, report.selection().meanRankDeltaShadowMinusDeterministic());
    }

    @Test
    void reportContainsCausalityDisclaimerAndCounterfactualLabel() {
        RankingEvaluationOfflineEvaluator.EvaluationReport report =
                RankingEvaluationOfflineEvaluator.evaluate(List.of(), List.of());
        String markdown = report.renderMarkdown();

        assertTrue(markdown.contains(RankingEvaluationOfflineEvaluator.CAUSALITY_DISCLAIMER));
        assertTrue(markdown.contains("COUNTERFACTUAL"));
        assertTrue(markdown.contains("COUNTERFACTUAL_POSITIONAL"));
        assertTrue(report.causalityDisclaimer().contains("NOT CAUSAL"));
    }

    @Test
    void dataSufficiencyInsufficientWhenLowCounts() {
        UUID evaluationId = UUID.randomUUID();
        RankingEvaluationSnapshot snapshot = snapshot(evaluationId, List.of(0), List.of(0));
        RankingEvaluationOutcomeRecord selected = outcome(
                evaluationId, 0, RankingEvaluationOutcomeType.RECOMMENDATION_SELECTED);

        RankingEvaluationOfflineEvaluator.EvaluationReport report =
                RankingEvaluationOfflineEvaluator.evaluate(List.of(snapshot), List.of(selected));

        assertEquals("INSUFFICIENT DATA", report.dataSufficiency().status());
        assertEquals(1, report.dataSufficiency().selectionCount());
        assertTrue(report.renderMarkdown().contains("INSUFFICIENT"));
    }

    private static RankingEvaluationSnapshot snapshot(
            UUID evaluationId, List<Integer> deterministic, List<Integer> shadow) {
        return new RankingEvaluationSnapshot(
                evaluationId,
                NOW,
                NOW.plusSeconds(3600),
                "DETERMINISTIC_V1",
                "APPLIED",
                "LOCAL_CHALLENGER_V1",
                "PARKING_SHADOW_FEATURES_V1",
                deterministic.size(),
                false,
                "MUNICIPAL_ONLY",
                deterministic,
                shadow,
                "[]",
                false,
                1);
    }

    private static RankingEvaluationOutcomeRecord outcome(
            UUID evaluationId, int ordinal, RankingEvaluationOutcomeType type) {
        return new RankingEvaluationOutcomeRecord(
                evaluationId,
                ordinal,
                type,
                NOW,
                RankingEvaluationPlatform.WEB,
                "0_5s");
    }
}
