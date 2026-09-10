package com.parkio.parking.application.recommendation.ranking.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.recommendation.CandidateAvailability;
import com.parkio.parking.application.recommendation.ParkingCandidate;
import com.parkio.parking.application.recommendation.ParkingCandidateChannel;
import com.parkio.parking.application.recommendation.RecommendationReason;
import com.parkio.parking.application.recommendation.RecommendationReasonCode;
import com.parkio.parking.application.recommendation.ranking.CandidateScoreBreakdown;
import com.parkio.parking.application.recommendation.ranking.RankingStatus;
import com.parkio.parking.application.recommendation.ranking.RankingVersion;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RankingEvaluationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private RankingEvaluationProperties properties;
    private FakeRankingEvaluationStore store;
    private RankingEvaluationService service;

    @BeforeEach
    void setUp() {
        properties = new RankingEvaluationProperties();
        properties.setEnabled(true);
        properties.setRetentionHours(24);
        properties.setCleanupEnabled(true);
        properties.setCleanupBatchSize(100);
        store = new FakeRankingEvaluationStore();
        service = new RankingEvaluationService(
                properties,
                store,
                new RankingEvaluationMetrics(new SimpleMeterRegistry()),
                new ObjectMapper().findAndRegisterModules(),
                CLOCK);
    }

    @Test
    void createEvaluationWhenEnabled() {
        UUID evaluationId = service.maybeCreateEvaluation(
                candidates(), RankingVersion.DETERMINISTIC_V1, RankingStatus.APPLIED, false, 1500);

        assertNotNull(evaluationId);
        assertEquals(1, store.snapshots().size());
        RankingEvaluationSnapshot snapshot = store.snapshots().getFirst();
        assertEquals(evaluationId, snapshot.evaluationId());
        assertEquals(2, snapshot.candidateCount());
        assertEquals(List.of(0, 1), snapshot.deterministicOrderByOrdinal());
        assertEquals(NOW.plusSeconds(24 * 3600), snapshot.expiresAt());
    }

    @Test
    void disabledReturnsNull() {
        properties.setEnabled(false);

        UUID evaluationId = service.maybeCreateEvaluation(
                candidates(), RankingVersion.DETERMINISTIC_V1, RankingStatus.APPLIED, false, 1500);

        assertNull(evaluationId);
        assertTrue(store.snapshots().isEmpty());
    }

    @Test
    void persistenceFailureFailOpenReturnsNull() {
        store.failNextInserts(true);

        UUID evaluationId = service.maybeCreateEvaluation(
                candidates(), RankingVersion.DETERMINISTIC_V1, RankingStatus.APPLIED, false, 1500);

        assertNull(evaluationId);
        assertTrue(store.snapshots().isEmpty());
    }

    @Test
    void recordOutcomeSuccessDuplicateAndInvalidOrdinal() {
        UUID evaluationId = service.maybeCreateEvaluation(
                candidates(), RankingVersion.DETERMINISTIC_V1, RankingStatus.APPLIED, false, 1500);

        assertEquals(
                RankingEvaluationService.OutcomeWriteResult.RECORDED,
                service.recordOutcome(
                        evaluationId,
                        0,
                        RankingEvaluationOutcomeType.RECOMMENDATION_SELECTED,
                        RankingEvaluationPlatform.WEB,
                        "0_5s"));
        assertEquals(
                RankingEvaluationService.OutcomeWriteResult.DUPLICATE,
                service.recordOutcome(
                        evaluationId,
                        0,
                        RankingEvaluationOutcomeType.RECOMMENDATION_SELECTED,
                        RankingEvaluationPlatform.WEB,
                        "0_5s"));

        ParkingException ordinalEx = assertThrows(
                ParkingException.class,
                () -> service.recordOutcome(
                        evaluationId,
                        99,
                        RankingEvaluationOutcomeType.NAVIGATION_STARTED,
                        RankingEvaluationPlatform.MOBILE_V2,
                        null));
        assertEquals(ParkingErrorCode.INVALID_RANKING_EVALUATION_OUTCOME, ordinalEx.errorCode());
    }

    @Test
    void recordOutcomeExpired() {
        UUID evaluationId = UUID.randomUUID();
        store.insertSnapshot(new RankingEvaluationSnapshot(
                evaluationId,
                NOW.minusSeconds(7200),
                NOW.minusSeconds(3600),
                RankingVersion.DETERMINISTIC_V1.name(),
                RankingStatus.APPLIED.name(),
                null,
                "PARKING_SHADOW_FEATURES_V1",
                2,
                false,
                "MUNICIPAL_ONLY",
                List.of(0, 1),
                null,
                "[{\"candidateOrdinal\":0,\"alias\":\"c0\",\"channel\":\"MUNICIPAL_FACILITY\","
                        + "\"distanceBucket\":\"0_100\",\"distanceNormalized\":0.1,"
                        + "\"occupancyFreshnessKind\":\"LIVE\",\"availabilityBucket\":\"10_plus\","
                        + "\"availabilityRatioBucket\":\"0_5_1\",\"capacityBucket\":\"50_plus\","
                        + "\"inventoryConfidenceBucket\":\"HIGH\",\"isFavourite\":false,"
                        + "\"reasonCodes\":[],\"deterministicScoreBucket\":\"50_75\","
                        + "\"deterministicPosition\":0},"
                        + "{\"candidateOrdinal\":1,\"alias\":\"c1\",\"channel\":\"MUNICIPAL_FACILITY\","
                        + "\"distanceBucket\":\"100_250\",\"distanceNormalized\":0.4,"
                        + "\"occupancyFreshnessKind\":\"LIVE\",\"availabilityBucket\":\"10_plus\","
                        + "\"availabilityRatioBucket\":\"0_5_1\",\"capacityBucket\":\"50_plus\","
                        + "\"inventoryConfidenceBucket\":\"HIGH\",\"isFavourite\":false,"
                        + "\"reasonCodes\":[],\"deterministicScoreBucket\":\"25_50\","
                        + "\"deterministicPosition\":1}]",
                null,
                null));

        ParkingException ex = assertThrows(
                ParkingException.class,
                () -> service.recordOutcome(
                        evaluationId,
                        0,
                        RankingEvaluationOutcomeType.RECOMMENDATION_SELECTED,
                        RankingEvaluationPlatform.WEB,
                        null));
        assertEquals(ParkingErrorCode.RANKING_EVALUATION_EXPIRED, ex.errorCode());
    }

    @Test
    void cleanupDeletesExpired() {
        RankingEvaluationSnapshot expired = new RankingEvaluationSnapshot(
                UUID.randomUUID(),
                NOW.minusSeconds(10_000),
                NOW.minusSeconds(1),
                RankingVersion.DETERMINISTIC_V1.name(),
                RankingStatus.APPLIED.name(),
                null,
                "PARKING_SHADOW_FEATURES_V1",
                1,
                false,
                "MUNICIPAL_ONLY",
                List.of(0),
                null,
                "[{\"candidateOrdinal\":0,\"alias\":\"c0\",\"channel\":\"MUNICIPAL_FACILITY\","
                        + "\"distanceBucket\":\"0_100\",\"distanceNormalized\":0.1,"
                        + "\"occupancyFreshnessKind\":\"LIVE\",\"availabilityBucket\":\"10_plus\","
                        + "\"availabilityRatioBucket\":\"0_5_1\",\"capacityBucket\":\"50_plus\","
                        + "\"inventoryConfidenceBucket\":\"HIGH\",\"isFavourite\":false,"
                        + "\"reasonCodes\":[],\"deterministicScoreBucket\":\"50_75\","
                        + "\"deterministicPosition\":0}]",
                null,
                null);
        RankingEvaluationSnapshot live = new RankingEvaluationSnapshot(
                UUID.randomUUID(),
                NOW,
                NOW.plusSeconds(3600),
                RankingVersion.DETERMINISTIC_V1.name(),
                RankingStatus.APPLIED.name(),
                null,
                "PARKING_SHADOW_FEATURES_V1",
                1,
                false,
                "MUNICIPAL_ONLY",
                List.of(0),
                null,
                expired.featuresJson(),
                null,
                null);
        store.insertSnapshot(expired);
        store.insertSnapshot(live);

        assertEquals(1, service.cleanupExpired());
        assertEquals(1, store.snapshots().size());
        assertEquals(live.evaluationId(), store.snapshots().getFirst().evaluationId());
    }

    private static List<ParkingCandidate> candidates() {
        return List.of(
                candidate("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 50, 0.9),
                candidate("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 180, 0.4));
    }

    private static ParkingCandidate candidate(String refId, int distance, double score) {
        return new ParkingCandidate(
                "municipal:" + refId,
                ParkingCandidateChannel.MUNICIPAL_FACILITY,
                refId,
                "Title",
                38.45,
                27.2,
                distance,
                CandidateAvailability.municipal(
                        MunicipalOccupancyFreshness.LIVE, 10, 5, 40, "IZUM", null),
                "IZUM",
                distance < 100 ? 0 : 1,
                List.of(RecommendationReason.of(RecommendationReasonCode.CLOSE_TO_DESTINATION)),
                score,
                new CandidateScoreBreakdown(0.7, 0.8, 0.4, 0.7, 0.0),
                RankingVersion.DETERMINISTIC_V1.name());
    }
}
