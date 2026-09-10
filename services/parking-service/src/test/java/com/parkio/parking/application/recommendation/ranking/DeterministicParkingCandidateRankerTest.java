package com.parkio.parking.application.recommendation.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.parkio.parking.application.recommendation.CandidateAvailability;
import com.parkio.parking.application.recommendation.ParkingCandidate;
import com.parkio.parking.application.recommendation.ParkingCandidateChannel;
import com.parkio.parking.application.recommendation.RecommendationReason;
import com.parkio.parking.application.recommendation.RecommendationReasonCode;
import com.parkio.parking.domain.place.Destination;
import com.parkio.parking.domain.place.DestinationSource;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeterministicParkingCandidateRankerTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final Destination DEST =
            Destination.of("Forum", 38.45, 27.2, DestinationSource.MAP_PIN);
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FAV = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private RankingProperties.RankingConfiguration config;
    private DeterministicParkingCandidateRanker ranker;

    @BeforeEach
    void setUp() {
        RankingProperties props = new RankingProperties();
        props.setEnabled(true);
        props.validate();
        config = props.snapshot();
        ranker = new DeterministicParkingCandidateRanker(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void distanceScoreMonotonicAndCapped() {
        assertEquals(1.0, DeterministicParkingCandidateRanker.distanceScore(0, 1200), 1e-9);
        assertTrue(DeterministicParkingCandidateRanker.distanceScore(100, 1200)
                > DeterministicParkingCandidateRanker.distanceScore(600, 1200));
        assertEquals(0.0, DeterministicParkingCandidateRanker.distanceScore(1200, 1200), 1e-9);
        assertEquals(0.0, DeterministicParkingCandidateRanker.distanceScore(5000, 1200), 1e-9);
    }

    @Test
    void freshnessOrderingMunicipal() {
        ParkingCandidate live = municipal(FAV, 100, MunicipalOccupancyFreshness.LIVE, 10, 100);
        ParkingCandidate aging = municipal(UUID.randomUUID(), 100, MunicipalOccupancyFreshness.AGING, 10, 100);
        ParkingCandidate stale = municipal(UUID.randomUUID(), 100, MunicipalOccupancyFreshness.STALE, 10, 100);
        ParkingCandidate unavailable =
                municipal(UUID.randomUUID(), 100, MunicipalOccupancyFreshness.UNAVAILABLE, null, 100);
        assertEquals(1.0, DeterministicParkingCandidateRanker.freshnessScore(live, NOW));
        assertEquals(0.7, DeterministicParkingCandidateRanker.freshnessScore(aging, NOW));
        assertEquals(0.2, DeterministicParkingCandidateRanker.freshnessScore(stale, NOW));
        assertEquals(0.0, DeterministicParkingCandidateRanker.freshnessScore(unavailable, NOW));
    }

    @Test
    void communityFreshnessUsesExpiry() {
        ParkingCandidate fresh = community(
                UUID.randomUUID(), 50, "ACTIVE", NOW.plusSeconds(300));
        ParkingCandidate expired = community(
                UUID.randomUUID(), 50, "ACTIVE", NOW.minusSeconds(1));
        assertEquals(0.65, DeterministicParkingCandidateRanker.freshnessScore(fresh, NOW));
        assertEquals(0.0, DeterministicParkingCandidateRanker.freshnessScore(expired, NOW));
    }

    @Test
    void capacityUnknownAndCommunityAreZero() {
        ParkingCandidate osm = municipal(
                UUID.randomUUID(), 80, MunicipalOccupancyFreshness.UNAVAILABLE, null, 200);
        ParkingCandidate community = community(UUID.randomUUID(), 40, "VERIFIED", NOW.plusSeconds(60));
        ParkingCandidate zeroAvail = municipal(
                UUID.randomUUID(), 80, MunicipalOccupancyFreshness.LIVE, 0, 100);
        assertEquals(0.0, DeterministicParkingCandidateRanker.capacityScore(osm));
        assertEquals(0.0, DeterministicParkingCandidateRanker.capacityScore(community));
        assertEquals(0.0, DeterministicParkingCandidateRanker.capacityScore(zeroAvail));
        ParkingCandidate high = municipal(
                UUID.randomUUID(), 80, MunicipalOccupancyFreshness.LIVE, 90, 100);
        assertTrue(DeterministicParkingCandidateRanker.capacityScore(high) > 0.5);
    }

    @Test
    void confidenceLiveMunicipalHighest() {
        ParkingCandidate live = municipal(FAV, 100, MunicipalOccupancyFreshness.LIVE, 10, 100);
        ParkingCandidate aging = municipal(UUID.randomUUID(), 100, MunicipalOccupancyFreshness.AGING, 10, 100);
        ParkingCandidate osm = municipal(
                UUID.randomUUID(), 100, MunicipalOccupancyFreshness.UNAVAILABLE, null, 100);
        assertEquals(1.0, DeterministicParkingCandidateRanker.confidenceScore(live, NOW));
        assertEquals(0.75, DeterministicParkingCandidateRanker.confidenceScore(aging, NOW));
        assertEquals(0.4, DeterministicParkingCandidateRanker.confidenceScore(osm, NOW));
    }

    @Test
    void favouriteOnlyMunicipal() {
        assertEquals(
                1.0,
                DeterministicParkingCandidateRanker.favouriteScore(
                        municipal(FAV, 100, MunicipalOccupancyFreshness.LIVE, 10, 100), Set.of(FAV)));
        assertEquals(
                0.0,
                DeterministicParkingCandidateRanker.favouriteScore(
                        municipal(UUID.randomUUID(), 100, MunicipalOccupancyFreshness.LIVE, 10, 100),
                        Set.of(FAV)));
        assertEquals(
                0.0,
                DeterministicParkingCandidateRanker.favouriteScore(
                        community(UUID.randomUUID(), 40, "VERIFIED", NOW.plusSeconds(60)), Set.of(FAV)));
    }

    @Test
    void weightedTotalUsesConfigWeights() {
        CandidateScoreBreakdown breakdown = new CandidateScoreBreakdown(1, 1, 1, 1, 1);
        assertEquals(1.0, DeterministicParkingCandidateRanker.weightedTotal(breakdown, config), 1e-9);
        CandidateScoreBreakdown half = new CandidateScoreBreakdown(0.5, 0.5, 0.5, 0.5, 0.5);
        assertEquals(0.5, DeterministicParkingCandidateRanker.weightedTotal(half, config), 1e-9);
    }

    @Test
    void rankingOrdersByScoreThenDistance() {
        ParkingCandidate fartherFavourite = municipal(
                FAV, 400, MunicipalOccupancyFreshness.LIVE, 80, 100);
        ParkingCandidate closerWeak = municipal(
                UUID.randomUUID(), 50, MunicipalOccupancyFreshness.UNAVAILABLE, null, 40);
        ParkingCandidateRanker.RankingOutcome outcome = ranker.rank(new RankingContext(
                DEST, USER, 1500, List.of(closerWeak, fartherFavourite), Set.of(FAV), config));
        assertEquals(RankingStatus.APPLIED, outcome.status());
        assertEquals(RankingVersion.DETERMINISTIC_V1, outcome.version());
        assertEquals(fartherFavourite.id(), outcome.ranked().getFirst().id());
        assertNotNull(outcome.ranked().getFirst().score());
        assertNotNull(outcome.ranked().getFirst().scoreBreakdown());
        assertTrue(outcome.ranked().getFirst().reasons().stream()
                .anyMatch(r -> r.code() == RecommendationReasonCode.FAVOURITE));
    }

    @Test
    void disabledUsesExactBaselineOrder() {
        RankingProperties props = new RankingProperties();
        props.setEnabled(false);
        props.validate();
        ParkingCandidate a = municipal(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                200,
                MunicipalOccupancyFreshness.LIVE,
                5,
                40);
        ParkingCandidate b = municipal(
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                50,
                MunicipalOccupancyFreshness.UNAVAILABLE,
                null,
                40);
        ParkingCandidateRanker.RankingOutcome outcome = ranker.rank(new RankingContext(
                DEST, USER, 1500, List.of(a, b), Set.of(), props.snapshot()));
        assertEquals(RankingStatus.DISABLED, outcome.status());
        assertEquals(RankingVersion.DISTANCE_BASELINE_V1, outcome.version());
        assertEquals(b.id(), outcome.ranked().getFirst().id());
        assertNull(outcome.ranked().getFirst().score());
    }

    @Test
    void reasonsAreCappedAndDeterministic() {
        ParkingCandidate candidate = municipal(FAV, 40, MunicipalOccupancyFreshness.LIVE, 90, 100);
        CandidateScoreBreakdown breakdown = ranker.scoreFactors(candidate, Set.of(FAV), config);
        List<RecommendationReason> reasons =
                DeterministicParkingCandidateRanker.selectReasons(candidate, breakdown, Set.of(FAV));
        assertTrue(reasons.size() <= 3);
        assertEquals(RecommendationReasonCode.FAVOURITE, reasons.getFirst().code());
    }

    @Test
    void scoreBreakdownClampsNonFinite() {
        CandidateScoreBreakdown breakdown =
                new CandidateScoreBreakdown(Double.NaN, -1, 2, Double.POSITIVE_INFINITY, 0.5);
        assertEquals(0.0, breakdown.distance());
        assertEquals(0.0, breakdown.freshness());
        assertEquals(1.0, breakdown.capacity());
        assertEquals(0.0, breakdown.confidence());
        assertEquals(0.5, breakdown.favourite());
    }

    @Test
    void rankingIsDeterministicForSameInput() {
        List<ParkingCandidate> candidates = List.of(
                municipal(FAV, 120, MunicipalOccupancyFreshness.LIVE, 40, 100),
                community(UUID.randomUUID(), 80, "VERIFIED", NOW.plusSeconds(120)),
                municipal(UUID.randomUUID(), 90, MunicipalOccupancyFreshness.AGING, 20, 80));
        RankingContext context = new RankingContext(DEST, USER, 1500, candidates, Set.of(FAV), config);
        List<String> first = ranker.rank(context).ranked().stream().map(ParkingCandidate::id).toList();
        List<String> second = ranker.rank(context).ranked().stream().map(ParkingCandidate::id).toList();
        assertEquals(first, second);
    }

    private static ParkingCandidate municipal(
            UUID id,
            int distance,
            MunicipalOccupancyFreshness freshness,
            Integer available,
            Integer capacity) {
        return new ParkingCandidate(
                ParkingCandidate.municipalId(id.toString()),
                ParkingCandidateChannel.MUNICIPAL_FACILITY,
                id.toString(),
                "Facility",
                38.45,
                27.2,
                distance,
                CandidateAvailability.municipal(
                        freshness, available, null, capacity, "IZUM", NOW),
                "IZUM",
                0,
                List.of(RecommendationReason.of(RecommendationReasonCode.CLOSE_TO_DESTINATION)));
    }

    private static ParkingCandidate community(UUID id, int distance, String status, Instant expiresAt) {
        return new ParkingCandidate(
                ParkingCandidate.communityId(id.toString()),
                ParkingCandidateChannel.COMMUNITY_SPOT,
                id.toString(),
                "Spot",
                38.45,
                27.2,
                distance,
                CandidateAvailability.community(status, expiresAt),
                null,
                0,
                List.of(RecommendationReason.of(RecommendationReasonCode.COMMUNITY_FRESH)));
    }
}
