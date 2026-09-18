package com.parkio.parking.application.recommendation.ranking.evaluation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RankingEvaluationPrivacyGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static final String ALLOWLISTED_FEATURES = """
            [
              {
                "candidateOrdinal": 0,
                "alias": "c0",
                "channel": "MUNICIPAL_FACILITY",
                "distanceBucket": "0_100",
                "distanceNormalized": 0.2,
                "occupancyFreshnessKind": "LIVE",
                "availabilityBucket": "10_plus",
                "availabilityRatioBucket": "0_5_1",
                "capacityBucket": "50_plus",
                "inventoryConfidenceBucket": "HIGH",
                "isFavourite": false,
                "reasonCodes": ["CLOSE_TO_DESTINATION"],
                "deterministicScoreBucket": "75_100",
                "deterministicPosition": 0
              }
            ]
            """;

    @Test
    void allowlistedFeaturesJsonPasses() {
        assertDoesNotThrow(() ->
                RankingEvaluationPrivacyGuard.assertFeaturesJsonAllowed(MAPPER, ALLOWLISTED_FEATURES));
        assertDoesNotThrow(() ->
                RankingEvaluationPrivacyGuard.assertNoForbiddenFields(MAPPER, ALLOWLISTED_FEATURES));
    }

    @Test
    void unknownTopLevelFeatureKeyFails() {
        String json = """
                [{"candidateOrdinal":0,"alias":"c0","channel":"MUNICIPAL_FACILITY",
                "distanceBucket":"0_100","distanceNormalized":0.1,"occupancyFreshnessKind":"LIVE",
                "availabilityBucket":"10_plus","availabilityRatioBucket":"0_5_1","capacityBucket":"50_plus",
                "inventoryConfidenceBucket":"HIGH","isFavourite":false,"reasonCodes":[],
                "deterministicScoreBucket":"50_75","deterministicPosition":0,"secretExtra":true}]
                """;
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> RankingEvaluationPrivacyGuard.assertFeaturesJsonAllowed(MAPPER, json));
        assertTrue(ex.getMessage().contains("not allowlisted"));
    }

    @Test
    void nestedForbiddenKeysFail() {
        String[] forbiddenPayloads = {
            """
            [{"candidateOrdinal":0,"alias":"c0","channel":"MUNICIPAL_FACILITY",
            "distanceBucket":"0_100","distanceNormalized":0.1,"occupancyFreshnessKind":"LIVE",
            "availabilityBucket":"10_plus","availabilityRatioBucket":"0_5_1","capacityBucket":"50_plus",
            "inventoryConfidenceBucket":"HIGH","isFavourite":false,"reasonCodes":[],
            "deterministicScoreBucket":"50_75","deterministicPosition":0,"userId":"x"}]
            """,
            """
            [{"candidateOrdinal":0,"alias":"c0","channel":"MUNICIPAL_FACILITY",
            "distanceBucket":"0_100","distanceNormalized":0.1,"occupancyFreshnessKind":"LIVE",
            "availabilityBucket":"10_plus","availabilityRatioBucket":"0_5_1","capacityBucket":"50_plus",
            "inventoryConfidenceBucket":"HIGH","isFavourite":false,"reasonCodes":[],
            "deterministicScoreBucket":"50_75","deterministicPosition":0,"latitude":1}]
            """,
            """
            [{"candidateOrdinal":0,"alias":"c0","channel":"MUNICIPAL_FACILITY",
            "distanceBucket":"0_100","distanceNormalized":0.1,"occupancyFreshnessKind":"LIVE",
            "availabilityBucket":"10_plus","availabilityRatioBucket":"0_5_1","capacityBucket":"50_plus",
            "inventoryConfidenceBucket":"HIGH","isFavourite":false,"reasonCodes":[],
            "deterministicScoreBucket":"50_75","deterministicPosition":0,"facilityId":"f"}]
            """,
            """
            {"nested":{"user_id":"abc"}}
            """
        };
        for (String payload : forbiddenPayloads) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> RankingEvaluationPrivacyGuard.assertNoForbiddenFields(MAPPER, payload),
                    "expected reject for " + payload);
        }
    }

    @Test
    void ordinalListValidation() {
        assertDoesNotThrow(() ->
                RankingEvaluationPrivacyGuard.assertOrdinalListJson(MAPPER, "[0,1,2]", 3));

        assertThrows(
                IllegalArgumentException.class,
                () -> RankingEvaluationPrivacyGuard.assertOrdinalListJson(MAPPER, "[0,1]", 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> RankingEvaluationPrivacyGuard.assertOrdinalListJson(MAPPER, "[0,0,1]", 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> RankingEvaluationPrivacyGuard.assertOrdinalListJson(MAPPER, "[0,1,3]", 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> RankingEvaluationPrivacyGuard.assertOrdinalListJson(MAPPER, "{\"a\":1}", 1));
    }
}
