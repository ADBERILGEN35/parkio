package com.parkio.parking.application.recommendation.ranking.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LocalChallengerShadowParkingRankerTest {

    @Test
    void producesValidOutputPreferringFreshness() {
        ShadowRankingRequest request = new ShadowRankingRequest(
                ShadowRankingConstants.REQUEST_SCHEMA_VERSION,
                ShadowRankingConstants.FEATURE_SCHEMA_VERSION,
                2,
                "500_1200",
                ShadowInventoryComposition.MUNICIPAL_ONLY,
                false,
                List.of(
                        features("c0", "STALE", "ZERO", 0.9),
                        features("c1", "LIVE", "HIGH", 0.2)));

        LocalChallengerShadowParkingRanker ranker = new LocalChallengerShadowParkingRanker();
        ShadowRankingOutput output = ranker.rank(request);

        assertTrue(ShadowOutputValidator.isValid(request, output));
        assertEquals("c1", output.orderedCandidateAliases().getFirst());
        assertEquals(2, output.reasonCategories().get("c1").size());
        assertTrue(output.reasonCategories().get("c1").size() <= 2);
    }

    private static ShadowCandidateFeatures features(
            String alias, String freshness, String availability, double distanceNormalized) {
        int ordinal = Integer.parseInt(alias.substring(1));
        return new ShadowCandidateFeatures(
                ordinal,
                alias,
                "MUNICIPAL_FACILITY",
                distanceNormalized < 0.3 ? "0_200" : "1200_plus",
                distanceNormalized,
                freshness,
                availability,
                availability,
                "11_50",
                "50_75",
                false,
                List.of(),
                "50_75",
                ordinal);
    }
}
