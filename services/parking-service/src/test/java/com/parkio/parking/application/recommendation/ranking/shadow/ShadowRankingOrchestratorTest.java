package com.parkio.parking.application.recommendation.ranking.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.parkio.parking.application.recommendation.CandidateAvailability;
import com.parkio.parking.application.recommendation.ParkingCandidate;
import com.parkio.parking.application.recommendation.ParkingCandidateChannel;
import com.parkio.parking.application.recommendation.RecommendationReason;
import com.parkio.parking.application.recommendation.RecommendationReasonCode;
import com.parkio.parking.application.recommendation.ranking.CandidateScoreBreakdown;
import com.parkio.parking.application.recommendation.ranking.RankingStatus;
import com.parkio.parking.application.recommendation.ranking.RankingVersion;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShadowRankingOrchestratorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-07T12:00:00Z"), ZoneOffset.UTC);
    private static final Executor SYNC = Runnable::run;

    private ShadowRankingProperties properties;
    private BoundedShadowEvaluationStore store;
    private ShadowRankingMetrics metrics;

    @BeforeEach
    void setUp() {
        properties = new ShadowRankingProperties();
        properties.setEnabled(true);
        properties.setSampleRate(1.0);
        properties.setTimeoutMs(200L);
        properties.setMaxCandidates(10);
        properties.setMaxConcurrent(4);
        store = new BoundedShadowEvaluationStore();
        metrics = new ShadowRankingMetrics(new SimpleMeterRegistry());
    }

    @Test
    void disabledSkipsWithoutCallingRanker() {
        properties.setEnabled(false);
        FakeShadowParkingRanker ranker = new FakeShadowParkingRanker(FakeShadowParkingRanker.Mode.IDENTITY);
        ShadowRankingOrchestrator orchestrator = orchestrator(ranker);

        orchestrator.maybeEvaluateAsync(
                RankingStatus.APPLIED, RankingVersion.DETERMINISTIC_V1, candidates(), false, 1500, SYNC);

        assertEquals(0, ranker.invocations());
        assertTrue(store.snapshot().isEmpty(), "disabled shadow must not fill evaluation store");
    }

    @Test
    void sampleRateZeroIsNotSampled() {
        properties.setSampleRate(0.0);
        FakeShadowParkingRanker ranker = new FakeShadowParkingRanker(FakeShadowParkingRanker.Mode.IDENTITY);
        ShadowRankingOrchestrator orchestrator = orchestrator(ranker);

        orchestrator.maybeEvaluateAsync(
                RankingStatus.APPLIED, RankingVersion.DETERMINISTIC_V1, candidates(), false, 1500, SYNC);

        assertEquals(0, ranker.invocations());
        assertEquals(ShadowRankingStatus.NOT_SAMPLED, latest().status());
    }

    @Test
    void sampleRateOneSucceeds() {
        FakeShadowParkingRanker ranker = new FakeShadowParkingRanker(FakeShadowParkingRanker.Mode.IDENTITY);
        ShadowRankingOrchestrator orchestrator = orchestrator(ranker);

        orchestrator.maybeEvaluateAsync(
                RankingStatus.APPLIED, RankingVersion.DETERMINISTIC_V1, candidates(), false, 1500, SYNC);

        assertEquals(1, ranker.invocations());
        assertEquals(ShadowRankingStatus.SUCCESS, latest().status());
        assertTrue(latest().comparison().top1Agreement());
    }

    @Test
    void timeoutRecordsTimeoutStatus() {
        properties.setTimeoutMs(50L);
        FakeShadowParkingRanker ranker =
                new FakeShadowParkingRanker(FakeShadowParkingRanker.Mode.TIMEOUT, List.of(), 500L);
        ShadowRankingOrchestrator orchestrator = orchestrator(ranker);

        orchestrator.maybeEvaluateAsync(
                RankingStatus.APPLIED, RankingVersion.DETERMINISTIC_V1, candidates(), false, 1500, SYNC);

        assertEquals(ShadowRankingStatus.TIMEOUT, latest().status());
    }

    @Test
    void throwRecordsProviderError() {
        FakeShadowParkingRanker ranker = new FakeShadowParkingRanker(FakeShadowParkingRanker.Mode.THROW);
        ShadowRankingOrchestrator orchestrator = orchestrator(ranker);

        orchestrator.maybeEvaluateAsync(
                RankingStatus.APPLIED, RankingVersion.DETERMINISTIC_V1, candidates(), false, 1500, SYNC);

        assertEquals(ShadowRankingStatus.PROVIDER_ERROR, latest().status());
    }

    @Test
    void invalidOutputRecordsInvalid() {
        FakeShadowParkingRanker ranker = new FakeShadowParkingRanker(FakeShadowParkingRanker.Mode.MALFORMED);
        ShadowRankingOrchestrator orchestrator = orchestrator(ranker);

        orchestrator.maybeEvaluateAsync(
                RankingStatus.APPLIED, RankingVersion.DETERMINISTIC_V1, candidates(), false, 1500, SYNC);

        assertEquals(ShadowRankingStatus.INVALID_OUTPUT, latest().status());
    }

    @Test
    void rankingNotAppliedIsDisabled() {
        FakeShadowParkingRanker ranker = new FakeShadowParkingRanker(FakeShadowParkingRanker.Mode.IDENTITY);
        ShadowRankingOrchestrator orchestrator = orchestrator(ranker);

        orchestrator.maybeEvaluateAsync(
                RankingStatus.DISABLED, RankingVersion.DISTANCE_BASELINE_V1, candidates(), false, 1500, SYNC);

        assertEquals(0, ranker.invocations());
        assertTrue(store.snapshot().isEmpty(), "non-applied ranking must not fill shadow store");
    }

    private ShadowRankingOrchestrator orchestrator(ShadowParkingRanker ranker) {
        return new ShadowRankingOrchestrator(properties, ranker, metrics, store, CLOCK);
    }

    private ShadowEvaluationRecord latest() {
        List<ShadowEvaluationRecord> snap = store.snapshot();
        assertTrue(!snap.isEmpty());
        return snap.getLast();
    }

    private static List<ParkingCandidate> candidates() {
        return List.of(
                candidate("c-a", 50, MunicipalOccupancyFreshness.LIVE, 0.9),
                candidate("c-b", 120, MunicipalOccupancyFreshness.STALE, 0.4));
    }

    private static ParkingCandidate candidate(
            String refSuffix, int distance, MunicipalOccupancyFreshness freshness, double score) {
        String refId = switch (refSuffix) {
            case "c-a" -> "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
            default -> "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        };
        return new ParkingCandidate(
                "municipal:" + refId,
                ParkingCandidateChannel.MUNICIPAL_FACILITY,
                refId,
                "Title-" + refSuffix,
                38.45,
                27.2,
                distance,
                CandidateAvailability.municipal(freshness, 10, 5, 40, "IZUM", null),
                "IZUM",
                distance < 100 ? 0 : 1,
                List.of(RecommendationReason.of(RecommendationReasonCode.CLOSE_TO_DESTINATION)),
                score,
                new CandidateScoreBreakdown(0.7, 0.8, 0.4, 0.7, 0.0),
                RankingVersion.DETERMINISTIC_V1.name());
    }
}
