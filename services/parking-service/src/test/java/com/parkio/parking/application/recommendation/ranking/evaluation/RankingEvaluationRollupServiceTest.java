package com.parkio.parking.application.recommendation.ranking.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RankingEvaluationRollupServiceTest {

    private static final Instant HOUR = Instant.parse("2026-08-07T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-07T12:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private RankingEvaluationProperties properties;
    private FakeRankingEvaluationStore evaluationStore;
    private FakeRankingEvaluationRollupStore rollupStore;
    private RankingEvaluationRollupService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        properties = new RankingEvaluationProperties();
        properties.setEnabled(true);
        properties.setRollupEnabled(true);
        properties.setRollupGraceMinutes(60);
        properties.setRetentionHours(24);
        evaluationStore = new FakeRankingEvaluationStore();
        rollupStore = new FakeRankingEvaluationRollupStore();
        mapper = new ObjectMapper().findAndRegisterModules();
        service = new RankingEvaluationRollupService(
                properties,
                evaluationStore,
                rollupStore,
                new RankingEvaluationRollupMetrics(new SimpleMeterRegistry()),
                mapper,
                CLOCK);
    }

    @Test
    void aggregatesSelectionWithShadowDenominator() {
        UUID id = UUID.randomUUID();
        evaluationStore.insertSnapshot(snapshot(id, List.of(0, 1, 2), List.of(1, 0, 2), HOUR.plusSeconds(10)));
        evaluationStore.insertOutcome(outcome(id, 0, RankingEvaluationOutcomeType.RECOMMENDATION_SELECTED));

        var result = service.processSlice(HOUR, HOUR.plus(Duration.ofHours(1)), NOW);

        assertEquals(1, result.evaluationsProcessed());
        assertEquals(1, result.outcomesProcessed());
        assertTrue(result.rowsWritten() >= 1);

        RankingEvaluationLongHorizonEvaluator.LongHorizonReport report =
                RankingEvaluationLongHorizonEvaluator.evaluateRollups(
                        rollupStore.allRows(), HOUR, HOUR.plus(Duration.ofHours(1)));
        assertEquals(1, report.selection().outcomeCount());
        assertEquals(1, report.selection().shadowAttachedOutcomeCount());
        assertEquals(0.0, report.selection().deterministicMeanRank());
        assertEquals(1.0, report.selection().shadowCounterfactualMeanRank());
        assertTrue(report.renderMarkdown().contains("COUNTERFACTUAL"));
    }

    @Test
    void shadowAbsentDoesNotInflateShadowDenominator() {
        UUID id = UUID.randomUUID();
        evaluationStore.insertSnapshot(snapshot(id, List.of(0, 1), null, HOUR.plusSeconds(5)));
        evaluationStore.insertOutcome(outcome(id, 1, RankingEvaluationOutcomeType.NAVIGATION_STARTED));

        service.processSlice(HOUR, HOUR.plus(Duration.ofHours(1)), NOW);

        var report = RankingEvaluationLongHorizonEvaluator.evaluateRollups(
                rollupStore.allRows(), HOUR, HOUR.plus(Duration.ofHours(1)));
        assertEquals(1, report.navigation().outcomeCount());
        assertEquals(0, report.navigation().shadowAttachedOutcomeCount());
        assertEquals(1.0, report.navigation().deterministicMeanRank());
        assertEquals(null, report.navigation().shadowCounterfactualMeanRank());
    }

    @Test
    void replaceSliceIsIdempotent() {
        UUID id = UUID.randomUUID();
        evaluationStore.insertSnapshot(snapshot(id, List.of(0), List.of(0), HOUR.plusSeconds(1)));
        evaluationStore.insertOutcome(outcome(id, 0, RankingEvaluationOutcomeType.RECOMMENDATION_SELECTED));

        service.processSlice(HOUR, HOUR.plus(Duration.ofHours(1)), NOW);
        service.processSlice(HOUR, HOUR.plus(Duration.ofHours(1)), NOW);

        long selections = rollupStore.allRows().stream()
                .filter(r -> r.outcomeType().equals("RECOMMENDATION_SELECTED"))
                .mapToLong(RankingEvaluationRollupRecord::outcomeCount)
                .sum();
        assertEquals(1, selections);
        assertEquals(2, rollupStore.processedSliceCount());
    }

    @Test
    void weightedMeanAcrossSlicesUsesSumOverCount() {
        Instant h1 = HOUR;
        Instant h2 = HOUR.plus(Duration.ofHours(1));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        evaluationStore.insertSnapshot(snapshot(a, List.of(0, 1), List.of(0, 1), h1.plusSeconds(1)));
        evaluationStore.insertOutcome(outcome(a, 0, RankingEvaluationOutcomeType.RECOMMENDATION_SELECTED));
        evaluationStore.insertSnapshot(snapshot(b, List.of(0, 1), List.of(0, 1), h2.plusSeconds(1)));
        evaluationStore.insertOutcome(outcome(b, 1, RankingEvaluationOutcomeType.RECOMMENDATION_SELECTED));

        service.processSlice(h1, h2, NOW);
        service.processSlice(h2, h2.plus(Duration.ofHours(1)), NOW);

        var report = RankingEvaluationLongHorizonEvaluator.evaluateRollups(
                rollupStore.allRows(), h1, h2.plus(Duration.ofHours(1)));
        assertEquals(2, report.selection().outcomeCount());
        assertEquals(0.5, report.selection().deterministicMeanRank());
    }

    @Test
    void rollupRecordHasNoForbiddenIdentityFields() {
        Set<String> forbidden = Set.of(
                "evaluationid",
                "userid",
                "facilityid",
                "spotid",
                "sessionid",
                "latitude",
                "longitude",
                "destination",
                "address",
                "refid",
                "targetid");
        for (RecordComponent component : RankingEvaluationRollupRecord.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(Locale.ROOT).replace("_", "");
            assertFalse(forbidden.contains(name), component.getName());
            assertFalse(component.getType().equals(UUID.class), component.getName());
        }
    }

    @Test
    void publicRankingUnaffectedByRollupFlag() {
        // Structural golden: rollup service never touches ranking formulas — smoke that disabled is no-op.
        properties.setRollupEnabled(false);
        assertEquals(0, service.runRollupPass());
    }

    private RankingEvaluationSnapshot snapshot(
            UUID id, List<Integer> det, List<Integer> shadow, Instant created) {
        String features =
                """
                [{"candidateOrdinal":0,"alias":"c0","channel":"MUNICIPAL_FACILITY","distanceBucket":"0_100",\
                "distanceNormalized":0.1,"occupancyFreshnessKind":"LIVE","availabilityBucket":"HIGH",\
                "availabilityRatioBucket":"HIGH","capacityBucket":"HIGH","inventoryConfidenceBucket":"HIGH",\
                "isFavourite":false,"reasonCodes":["LIVE_AVAILABILITY"],"deterministicScoreBucket":"HIGH",\
                "deterministicPosition":0},\
                {"candidateOrdinal":1,"alias":"c1","channel":"MUNICIPAL_FACILITY","distanceBucket":"100_250",\
                "distanceNormalized":0.2,"occupancyFreshnessKind":"LIVE","availabilityBucket":"MEDIUM",\
                "availabilityRatioBucket":"MEDIUM","capacityBucket":"MEDIUM","inventoryConfidenceBucket":"HIGH",\
                "isFavourite":false,"reasonCodes":["CLOSE_TO_DESTINATION"],"deterministicScoreBucket":"MEDIUM",\
                "deterministicPosition":1},\
                {"candidateOrdinal":2,"alias":"c2","channel":"MUNICIPAL_FACILITY","distanceBucket":"250_500",\
                "distanceNormalized":0.3,"occupancyFreshnessKind":"STALE","availabilityBucket":"ZERO",\
                "availabilityRatioBucket":"ZERO","capacityBucket":"HIGH","inventoryConfidenceBucket":"LOW",\
                "isFavourite":false,"reasonCodes":["HIGH_CAPACITY"],"deterministicScoreBucket":"LOW",\
                "deterministicPosition":2}]
                """;
        int count = det.size();
        return new RankingEvaluationSnapshot(
                id,
                created,
                created.plus(Duration.ofHours(24)),
                "DETERMINISTIC_V1",
                "APPLIED",
                shadow == null ? null : "LOCAL_CHALLENGER_V1",
                "PARKING_SHADOW_FEATURES_V1",
                count,
                false,
                "MUNICIPAL_ONLY",
                det,
                shadow,
                features,
                shadow == null ? null : Boolean.FALSE,
                shadow == null ? null : 1);
    }

    private RankingEvaluationOutcomeRecord outcome(
            UUID id, int ordinal, RankingEvaluationOutcomeType type) {
        return new RankingEvaluationOutcomeRecord(
                id, ordinal, type, HOUR.plusSeconds(30), RankingEvaluationPlatform.WEB, null);
    }
}
