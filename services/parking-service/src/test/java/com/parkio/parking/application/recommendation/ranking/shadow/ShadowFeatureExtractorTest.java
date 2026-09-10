package com.parkio.parking.application.recommendation.ranking.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.recommendation.CandidateAvailability;
import com.parkio.parking.application.recommendation.ParkingCandidate;
import com.parkio.parking.application.recommendation.ParkingCandidateChannel;
import com.parkio.parking.application.recommendation.RecommendationReason;
import com.parkio.parking.application.recommendation.RecommendationReasonCode;
import com.parkio.parking.application.recommendation.ranking.CandidateScoreBreakdown;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ShadowFeatureExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static final String[] FORBIDDEN = {
        "userId",
        "userid",
        "latitude",
        "longitude",
        "label",
        "address",
        "facilityId",
        "facilityid",
        "refId",
        "refid",
        "sessionId",
        "sessionid",
        "email"
    };

    @Test
    void privacyMinimizedJsonOmitsForbiddenStrings() throws Exception {
        List<ParkingCandidate> candidates = List.of(
                municipal("municipal:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "Secret Title", 38.45, 27.2, 80),
                community("community:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "Home Address 12", 38.451, 27.21, 200));

        ShadowRankingRequest request = ShadowFeatureExtractor.extract(candidates, true, 1500);
        String json = MAPPER.writeValueAsString(request).toLowerCase(Locale.ROOT);

        for (String forbidden : FORBIDDEN) {
            assertFalse(json.contains(forbidden.toLowerCase(Locale.ROOT)), "must not contain " + forbidden);
        }
        assertFalse(json.contains("secret title"));
        assertFalse(json.contains("home address"));
        assertFalse(json.contains("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        assertFalse(json.contains("38.45"));
        assertFalse(json.contains("27.2"));

        assertEquals("c0", request.candidates().get(0).alias());
        assertEquals("c1", request.candidates().get(1).alias());
        assertEquals(ShadowInventoryComposition.MIXED, request.inventoryComposition());
        assertTrue(request.partial());
    }

    @Test
    void favouriteFromBreakdownOrReason() {
        ParkingCandidate favourite = new ParkingCandidate(
                "municipal:cccccccc-cccc-cccc-cccc-cccccccccccc",
                ParkingCandidateChannel.MUNICIPAL_FACILITY,
                "cccccccc-cccc-cccc-cccc-cccccccccccc",
                "Fave",
                38.45,
                27.2,
                100,
                CandidateAvailability.municipal(
                        MunicipalOccupancyFreshness.LIVE, 10, 5, 40, "IZUM", null),
                "IZUM",
                0,
                List.of(RecommendationReason.of(RecommendationReasonCode.FAVOURITE)),
                0.9,
                new CandidateScoreBreakdown(0.5, 1.0, 0.5, 1.0, 1.0),
                "DETERMINISTIC_V1");
        ShadowCandidateFeatures features = ShadowFeatureExtractor.toFeatures(favourite, 0);
        assertTrue(features.isFavourite());
        assertTrue(features.reasonCodes().contains("FAVOURITE"));
    }

    private static ParkingCandidate municipal(
            String id, String title, double lat, double lng, int distance) {
        return new ParkingCandidate(
                id,
                ParkingCandidateChannel.MUNICIPAL_FACILITY,
                id.substring("municipal:".length()),
                title,
                lat,
                lng,
                distance,
                CandidateAvailability.municipal(
                        MunicipalOccupancyFreshness.LIVE, 20, 10, 100, "IZUM", null),
                "IZUM",
                0,
                List.of(RecommendationReason.of(RecommendationReasonCode.LIVE_AVAILABILITY)),
                0.8,
                new CandidateScoreBreakdown(0.7, 1.0, 0.6, 1.0, 0.0),
                "DETERMINISTIC_V1");
    }

    private static ParkingCandidate community(
            String id, String title, double lat, double lng, int distance) {
        return new ParkingCandidate(
                id,
                ParkingCandidateChannel.COMMUNITY_SPOT,
                id.substring("community:".length()),
                title,
                lat,
                lng,
                distance,
                CandidateAvailability.community("VERIFIED", null),
                "community",
                1,
                List.of(RecommendationReason.of(RecommendationReasonCode.COMMUNITY_FRESH)),
                0.4,
                new CandidateScoreBreakdown(0.5, 0.65, 0.0, 0.45, 0.0),
                "DETERMINISTIC_V1");
    }
}
