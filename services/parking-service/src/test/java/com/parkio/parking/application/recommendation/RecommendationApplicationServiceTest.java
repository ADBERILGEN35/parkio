package com.parkio.parking.application.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.MunicipalFacilityQueryService.FacilityView;
import com.parkio.parking.application.ParkingApplicationService;
import com.parkio.parking.application.command.SearchNearbyQuery;
import com.parkio.parking.application.port.FavouriteFacilityLookupPort;
import com.parkio.parking.application.recommendation.ranking.DeterministicParkingCandidateRanker;
import com.parkio.parking.application.recommendation.ranking.RankingMetrics;
import com.parkio.parking.application.recommendation.ranking.RankingProperties;
import com.parkio.parking.application.recommendation.ranking.RankingStatus;
import com.parkio.parking.application.recommendation.ranking.RankingVersion;
import com.parkio.parking.application.recommendation.ranking.shadow.BoundedShadowEvaluationStore;
import com.parkio.parking.application.recommendation.ranking.shadow.FakeShadowParkingRanker;
import com.parkio.parking.application.recommendation.ranking.shadow.ShadowRankingMetrics;
import com.parkio.parking.application.recommendation.ranking.shadow.ShadowRankingOrchestrator;
import com.parkio.parking.application.recommendation.ranking.shadow.ShadowRankingProperties;
import com.parkio.parking.application.recommendation.ranking.shadow.ShadowRankingStatus;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.VehicleType;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.domain.place.Destination;
import com.parkio.parking.domain.place.DestinationSource;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecommendationApplicationServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final Destination DEST = Destination.of(
            "Forum Bornova", 38.45, 27.2, DestinationSource.GEOCODING);

    private ParkingApplicationService community;
    private MunicipalFacilityQueryService municipal;
    private FavouriteFacilityLookupPort favourites;
    private RankingProperties rankingProperties;
    private RecommendationApplicationService service;
    private Clock clock;

    @BeforeEach
    void setUp() {
        community = mock(ParkingApplicationService.class);
        municipal = mock(MunicipalFacilityQueryService.class);
        favourites = mock(FavouriteFacilityLookupPort.class);
        rankingProperties = new RankingProperties();
        rankingProperties.validate();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Executor sync = Runnable::run;
        service = new RecommendationApplicationService(
                community,
                municipal,
                favourites,
                new DeterministicParkingCandidateRanker(clock),
                rankingProperties,
                new RankingMetrics(new SimpleMeterRegistry()),
                disabledShadowOrchestrator(),
                null,
                clock,
                new RecommendationMetrics(new SimpleMeterRegistry()),
                sync);
    }

    @Test
    void rankingOnWithShadowReverseChallengerDoesNotChangePublicOrder() {
        rankingProperties.setEnabled(true);
        rankingProperties.validate();
        UUID municipalId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        when(favourites.favouritedMunicipalFacilityIds(eq(USER), anyCollection()))
                .thenReturn(Set.of());
        when(community.searchNearby(any())).thenReturn(List.of(
                spot("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 38.45005, 27.20005, "Close community"),
                spot("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 38.4515, 27.2015, "Far community")));
        when(municipal.nearby(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(
                facility(municipalId.toString(), "Live municipal", 38.4508, 27.2008,
                        MunicipalOccupancyFreshness.LIVE, 40, 80)));

        RecommendationResult withoutShadow = service.recommend(query(true, true, 10));

        ShadowRankingProperties shadowProps = new ShadowRankingProperties();
        shadowProps.setEnabled(true);
        shadowProps.setSampleRate(1.0);
        shadowProps.setTimeoutMs(500L);
        BoundedShadowEvaluationStore store = new BoundedShadowEvaluationStore();
        ShadowRankingOrchestrator reverseShadow = new ShadowRankingOrchestrator(
                shadowProps,
                new FakeShadowParkingRanker(FakeShadowParkingRanker.Mode.REVERSE),
                new ShadowRankingMetrics(new SimpleMeterRegistry()),
                store,
                null,
                clock);
        RecommendationApplicationService withShadow = new RecommendationApplicationService(
                community,
                municipal,
                favourites,
                new DeterministicParkingCandidateRanker(clock),
                rankingProperties,
                new RankingMetrics(new SimpleMeterRegistry()),
                reverseShadow,
                null,
                clock,
                new RecommendationMetrics(new SimpleMeterRegistry()),
                Runnable::run);

        RecommendationResult withShadowOn = withShadow.recommend(query(true, true, 10));

        assertEquals(RankingStatus.APPLIED, withShadowOn.rankingStatus());
        assertEquals(withoutShadow.rankingVersion(), withShadowOn.rankingVersion());
        assertEquals(withoutShadow.candidates().size(), withShadowOn.candidates().size());
        for (int i = 0; i < withoutShadow.candidates().size(); i++) {
            assertEquals(
                    withoutShadow.candidates().get(i).id(),
                    withShadowOn.candidates().get(i).id());
            assertEquals(
                    withoutShadow.candidates().get(i).score(),
                    withShadowOn.candidates().get(i).score());
            assertEquals(
                    withoutShadow.candidates().get(i).reasons(),
                    withShadowOn.candidates().get(i).reasons());
            assertEquals(
                    withoutShadow.candidates().get(i).rankingVersion(),
                    withShadowOn.candidates().get(i).rankingVersion());
        }
        assertTrue(store.snapshot().stream().anyMatch(r -> r.status() == ShadowRankingStatus.SUCCESS));
    }

    private ShadowRankingOrchestrator disabledShadowOrchestrator() {
        ShadowRankingProperties shadowProps = new ShadowRankingProperties();
        shadowProps.setEnabled(false);
        shadowProps.setSampleRate(0.0);
        return new ShadowRankingOrchestrator(
                shadowProps,
                new FakeShadowParkingRanker(FakeShadowParkingRanker.Mode.IDENTITY),
                new ShadowRankingMetrics(new SimpleMeterRegistry()),
                new BoundedShadowEvaluationStore(),
                null,
                clock);
    }

    @Test
    void ordersByDistanceAscendingWithStableTieBreak() {
        when(community.searchNearby(any(SearchNearbyQuery.class))).thenReturn(List.of(
                spot("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 38.4502, 27.2002, "Farther"),
                spot("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 38.45005, 27.20005, "Closer")));
        when(municipal.nearby(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(
                facility("cccccccc-cccc-cccc-cccc-cccccccccccc", "Municipal", 38.4501, 27.2001,
                        MunicipalOccupancyFreshness.LIVE, 10, 100)));

        RecommendationResult result = service.recommend(query(true, true, 10));

        assertFalse(result.partial());
        assertEquals(3, result.candidates().size());
        assertEquals(RankingStatus.DISABLED, result.rankingStatus());
        assertEquals(RankingVersion.DISTANCE_BASELINE_V1, result.rankingVersion());
        assertEquals(0, result.candidates().get(0).baselineOrder());
        assertTrue(result.candidates().get(0).distanceMeters()
                <= result.candidates().get(1).distanceMeters());
        assertTrue(result.candidates().get(1).distanceMeters()
                <= result.candidates().get(2).distanceMeters());
        assertEquals(InventoryChannelStatus.AVAILABLE, result.communityStatus());
        assertEquals(InventoryChannelStatus.AVAILABLE, result.municipalStatus());
        verify(favourites, never()).favouritedMunicipalFacilityIds(any(), anyCollection());
    }

    @Test
    void rankingOnReordersByScoreAndLooksUpFavourites() {
        rankingProperties.setEnabled(true);
        rankingProperties.validate();
        UUID municipalId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        when(favourites.favouritedMunicipalFacilityIds(eq(USER), anyCollection()))
                .thenReturn(Set.of(municipalId));
        when(community.searchNearby(any())).thenReturn(List.of(
                spot("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 38.45005, 27.20005, "Close community")));
        when(municipal.nearby(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(
                facility(municipalId.toString(), "Favourite live", 38.451, 27.201,
                        MunicipalOccupancyFreshness.LIVE, 80, 100)));

        RecommendationResult result = service.recommend(query(true, true, 10));

        assertEquals(RankingStatus.APPLIED, result.rankingStatus());
        assertEquals(RankingVersion.DETERMINISTIC_V1, result.rankingVersion());
        assertEquals(ParkingCandidateChannel.MUNICIPAL_FACILITY, result.candidates().getFirst().channel());
        assertTrue(result.candidates().getFirst().score() != null
                && result.candidates().getFirst().score()
                        > result.candidates().get(1).score());
        assertTrue(result.candidates().getFirst().reasons().stream()
                .anyMatch(r -> r.code() == RecommendationReasonCode.FAVOURITE));
        verify(favourites).favouritedMunicipalFacilityIds(eq(USER), anyCollection());
    }

    @Test
    void favouriteLookupFailureDoesNotDegradeInventory() {
        rankingProperties.setEnabled(true);
        rankingProperties.validate();
        when(favourites.favouritedMunicipalFacilityIds(eq(USER), anyCollection())).thenReturn(Set.of());
        when(community.searchNearby(any())).thenReturn(List.of());
        when(municipal.nearby(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(
                facility("cccccccc-cccc-cccc-cccc-cccccccccccc", "C", 38.4501, 27.2001,
                        MunicipalOccupancyFreshness.LIVE, 5, 40)));

        RecommendationResult result = service.recommend(query(true, true, 10));
        assertFalse(result.partial());
        assertEquals(InventoryChannelStatus.EMPTY, result.communityStatus());
        assertEquals(InventoryChannelStatus.AVAILABLE, result.municipalStatus());
        assertEquals(RankingStatus.APPLIED, result.rankingStatus());
    }

    @Test
    void appliesGlobalLimitAfterMerge() {
        when(community.searchNearby(any())).thenReturn(List.of(
                spot("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 38.4501, 27.2001, "A"),
                spot("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 38.4502, 27.2002, "B")));
        when(municipal.nearby(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(
                facility("cccccccc-cccc-cccc-cccc-cccccccccccc", "C", 38.4503, 27.2003,
                        MunicipalOccupancyFreshness.UNAVAILABLE, null, 200)));

        RecommendationResult result = service.recommend(query(true, true, 2));
        assertEquals(2, result.candidates().size());
    }

    @Test
    void communityFailureIsPartialNotTotalFailure() {
        when(community.searchNearby(any())).thenThrow(new RuntimeException("boom"));
        when(municipal.nearby(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(
                facility("cccccccc-cccc-cccc-cccc-cccccccccccc", "C", 38.4501, 27.2001,
                        MunicipalOccupancyFreshness.LIVE, 5, 40)));

        RecommendationResult result = service.recommend(query(true, true, 10));
        assertTrue(result.partial());
        assertEquals(InventoryChannelStatus.DEGRADED, result.communityStatus());
        assertEquals(InventoryChannelStatus.AVAILABLE, result.municipalStatus());
        assertEquals(1, result.candidates().size());
        assertEquals(RecommendationReasonCode.INVENTORY_DEGRADED, result.warnings().getFirst().code());
    }

    @Test
    void municipalFailureIsPartial() {
        when(community.searchNearby(any())).thenReturn(List.of(
                spot("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 38.4501, 27.2001, "A")));
        when(municipal.nearby(anyDouble(), anyDouble(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("municipal down"));

        RecommendationResult result = service.recommend(query(true, true, 10));
        assertTrue(result.partial());
        assertEquals(InventoryChannelStatus.AVAILABLE, result.communityStatus());
        assertEquals(InventoryChannelStatus.DEGRADED, result.municipalStatus());
    }

    @Test
    void bothFailuresThrowUnavailable() {
        when(community.searchNearby(any())).thenThrow(new RuntimeException("c"));
        when(municipal.nearby(anyDouble(), anyDouble(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("m"));

        ParkingException ex = assertThrows(
                ParkingException.class, () -> service.recommend(query(true, true, 10)));
        assertEquals(ParkingErrorCode.RECOMMENDATION_INVENTORIES_UNAVAILABLE, ex.errorCode());
    }

    @Test
    void disabledChannelIsDisabledNotDegraded() {
        when(municipal.nearby(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of());

        RecommendationResult result = service.recommend(query(false, true, 10));
        assertEquals(InventoryChannelStatus.DISABLED, result.communityStatus());
        assertEquals(InventoryChannelStatus.EMPTY, result.municipalStatus());
        assertFalse(result.partial());
    }

    @Test
    void rejectsNoInventorySelected() {
        ParkingException ex = assertThrows(
                ParkingException.class, () -> service.recommend(query(false, false, 10)));
        assertEquals(ParkingErrorCode.INVENTORY_SELECTION_REQUIRED, ex.errorCode());
    }

    @Test
    void rejectsOversizedRadius() {
        ParkingException ex = assertThrows(
                ParkingException.class,
                () -> service.recommend(new RecommendationQuery(
                        USER, DEST, 5001, 10, true, true)));
        assertEquals(ParkingErrorCode.INVALID_RECOMMENDATION_RADIUS, ex.errorCode());
    }

    @Test
    void rejectsOversizedLimit() {
        ParkingException ex = assertThrows(
                ParkingException.class,
                () -> service.recommend(new RecommendationQuery(
                        USER, DEST, 1500, 51, true, true)));
        assertEquals(ParkingErrorCode.INVALID_RECOMMENDATION_LIMIT, ex.errorCode());
    }

    @Test
    void channelFetchLimitScalesAndCaps() {
        assertEquals(20, RecommendationApplicationService.channelFetchLimit(10, 50));
        assertEquals(50, RecommendationApplicationService.channelFetchLimit(40, 50));
        assertEquals(100, RecommendationApplicationService.channelFetchLimit(50, 100));
    }

    private RecommendationQuery query(boolean community, boolean municipal, int limit) {
        return new RecommendationQuery(USER, DEST, 1500, limit, community, municipal);
    }

    private static ParkingSpot spot(String id, double lat, double lng, String address) {
        Instant now = NOW;
        return new ParkingSpot(
                UUID.fromString(id),
                USER,
                UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                lat,
                lng,
                address,
                null,
                false,
                Set.of(VehicleType.SEDAN),
                ParkingContext.STREET_PARKING,
                LegalStatus.LEGAL,
                Set.of(),
                ParkingSpotStatus.VERIFIED,
                1.0,
                1,
                0,
                now.plusSeconds(600),
                now,
                now,
                0L,
                now,
                now.plusSeconds(86400),
                0,
                null,
                null,
                null);
    }

    private static FacilityView facility(
            String id,
            String name,
            double lat,
            double lng,
            MunicipalOccupancyFreshness freshness,
            Integer available,
            Integer capacity) {
        return new FacilityView(
                UUID.fromString(id),
                name,
                "Operator",
                MunicipalFacilityType.OFF_STREET,
                "Addr",
                lat,
                lng,
                capacity,
                available,
                available == null || capacity == null ? null : capacity - available,
                freshness,
                "attr",
                freshness == MunicipalOccupancyFreshness.LIVE ? "IZUM" : "OSM",
                NOW,
                freshness == MunicipalOccupancyFreshness.LIVE ? MunicipalSourceIdentity.IZUM : null);
    }
}
