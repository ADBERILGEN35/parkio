package com.parkio.parking.application.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.MunicipalFacilityQueryService.FacilityView;
import com.parkio.parking.application.ParkingApplicationService;
import com.parkio.parking.application.command.SearchNearbyQuery;
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
    private RecommendationApplicationService service;

    @BeforeEach
    void setUp() {
        community = mock(ParkingApplicationService.class);
        municipal = mock(MunicipalFacilityQueryService.class);
        Executor sync = Runnable::run;
        service = new RecommendationApplicationService(
                community,
                municipal,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new RecommendationMetrics(new SimpleMeterRegistry()),
                sync);
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
        assertEquals(0, result.candidates().get(0).baselineOrder());
        assertTrue(result.candidates().get(0).distanceMeters()
                <= result.candidates().get(1).distanceMeters());
        assertTrue(result.candidates().get(1).distanceMeters()
                <= result.candidates().get(2).distanceMeters());
        assertEquals(InventoryChannelStatus.AVAILABLE, result.communityStatus());
        assertEquals(InventoryChannelStatus.AVAILABLE, result.municipalStatus());
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
                NOW);
    }
}
