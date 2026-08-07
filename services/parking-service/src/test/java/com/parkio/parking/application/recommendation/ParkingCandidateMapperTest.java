package com.parkio.parking.application.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.parkio.parking.application.MunicipalFacilityQueryService.FacilityView;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.VehicleType;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.provider.ParkingProviderCatalog;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParkingCandidateMapperTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final double DEST_LAT = 38.45;
    private static final double DEST_LNG = 27.2;

    @Test
    void mapsLiveMunicipalWithLiveAvailabilityReason() {
        FacilityView view = facility(
                MunicipalOccupancyFreshness.LIVE, 12, 80, "Izmir Buyuksehir Belediyesi / IZUM");
        ParkingCandidate candidate = ParkingCandidateMapper.fromMunicipal(view, DEST_LAT, DEST_LNG);

        assertTrue(candidate.id().startsWith("municipal:"));
        assertEquals(ParkingCandidateChannel.MUNICIPAL_FACILITY, candidate.channel());
        assertEquals(CandidateAvailability.Kind.MUNICIPAL, candidate.availability().kind());
        assertEquals(12, candidate.availability().availableSpaces());
        assertEquals(MunicipalOccupancyFreshness.LIVE, candidate.availability().freshness());
        assertTrue(candidate.reasons().stream()
                .anyMatch(r -> r.code() == RecommendationReasonCode.LIVE_AVAILABILITY));
        assertTrue(candidate.reasons().stream()
                .anyMatch(r -> r.code() == RecommendationReasonCode.HIGH_CAPACITY));
    }

    @Test
    void mapsStaticOsmWithoutInventingLiveSpaces() {
        FacilityView view = facility(MunicipalOccupancyFreshness.UNAVAILABLE, null, 40, "OSM");
        ParkingCandidate candidate = ParkingCandidateMapper.fromMunicipal(view, DEST_LAT, DEST_LNG);

        assertNull(candidate.availability().availableSpaces());
        assertTrue(candidate.reasons().stream()
                .anyMatch(r -> r.code() == RecommendationReasonCode.STATIC_INVENTORY));
        assertTrue(candidate.reasons().stream()
                .noneMatch(r -> r.code() == RecommendationReasonCode.LIVE_AVAILABILITY));
    }

    @Test
    void mapsStaleWithoutPublishingAsLive() {
        FacilityView view = facility(MunicipalOccupancyFreshness.STALE, null, 100, "IZUM");
        ParkingCandidate candidate = ParkingCandidateMapper.fromMunicipal(view, DEST_LAT, DEST_LNG);

        assertNull(candidate.availability().availableSpaces());
        assertEquals(MunicipalOccupancyFreshness.STALE, candidate.availability().freshness());
        assertTrue(candidate.reasons().stream()
                .anyMatch(r -> r.code() == RecommendationReasonCode.STATIC_INVENTORY));
    }

    @Test
    void mapsCommunityWithoutMunicipalOccupancyFields() {
        ParkingSpot spot = communitySpot();
        ParkingCandidate candidate = ParkingCandidateMapper.fromCommunity(spot, DEST_LAT, DEST_LNG);

        assertTrue(candidate.id().startsWith("community:"));
        assertEquals(ParkingCandidateChannel.COMMUNITY_SPOT, candidate.channel());
        assertEquals(CandidateAvailability.Kind.COMMUNITY, candidate.availability().kind());
        assertNull(candidate.availability().freshness());
        assertNull(candidate.availability().availableSpaces());
        assertEquals("VERIFIED", candidate.availability().communityStatus());
        assertTrue(candidate.reasons().stream()
                .anyMatch(r -> r.code() == RecommendationReasonCode.COMMUNITY_FRESH));
        assertEquals("Alsancak Cad.", candidate.title());
    }

    @Test
    void preservesZeroAvailableSpaces() {
        FacilityView view = facility(MunicipalOccupancyFreshness.LIVE, 0, 50, "IZUM");
        ParkingCandidate candidate = ParkingCandidateMapper.fromMunicipal(view, DEST_LAT, DEST_LNG);
        assertEquals(0, candidate.availability().availableSpaces());
        assertTrue(candidate.reasons().stream()
                .anyMatch(r -> r.code() == RecommendationReasonCode.LIVE_AVAILABILITY));
    }

    @Test
    void mapsLiveIsparkMunicipalWithoutProviderSpecialCasing() {
        FacilityView view = new FacilityView(
                UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                "Kadıköy Açık Otopark",
                "İSPARK",
                MunicipalFacilityType.OFF_STREET,
                "KADIKÖY",
                40.9901,
                29.0292,
                120,
                45,
                75,
                MunicipalOccupancyFreshness.LIVE,
                ParkingProviderCatalog.ISPARK_ATTRIBUTION,
                ParkingProviderCatalog.ISPARK_DISPLAY_NAME,
                NOW,
                MunicipalSourceIdentity.ISPARK);
        ParkingCandidate candidate = ParkingCandidateMapper.fromMunicipal(view, 41.0, 29.0);

        assertEquals(ParkingCandidateChannel.MUNICIPAL_FACILITY, candidate.channel());
        assertEquals(45, candidate.availability().availableSpaces());
        assertTrue(candidate.reasons().stream()
                .anyMatch(r -> r.code() == RecommendationReasonCode.LIVE_AVAILABILITY));
        assertTrue(candidate.reasons().stream()
                .anyMatch(r -> r.code() == RecommendationReasonCode.HIGH_CAPACITY));
    }

    private static FacilityView facility(
            MunicipalOccupancyFreshness freshness,
            Integer available,
            Integer capacity,
            String sourceLabel) {
        return new FacilityView(
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                "Katlı Otopark",
                "Belediye",
                MunicipalFacilityType.OFF_STREET,
                "Bornova",
                38.4505,
                27.2005,
                capacity,
                available,
                available == null || capacity == null ? null : capacity - available,
                freshness,
                "attr",
                sourceLabel,
                NOW,
                available == null ? null : MunicipalSourceIdentity.IZUM);
    }

    private static ParkingSpot communitySpot() {
        return new ParkingSpot(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                38.4502,
                27.2002,
                "Alsancak Cad.",
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
                NOW.plusSeconds(600),
                NOW,
                NOW,
                0L,
                NOW,
                NOW.plusSeconds(86400),
                0,
                null,
                null,
                null);
    }
}
