package com.parkio.parking.application.recommendation.ranking.shadow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShadowOutputValidatorTest {

    @Test
    void acceptsExactAliasCover() {
        ShadowRankingRequest request = request("c0", "c1");
        ShadowRankingOutput output = new ShadowRankingOutput(
                ShadowRankingConstants.OUTPUT_SCHEMA_VERSION,
                List.of("c1", "c0"),
                ShadowConfidence.HIGH,
                Map.of(
                        "c0", List.of(ShadowReasonCategory.DISTANCE),
                        "c1", List.of(ShadowReasonCategory.FRESHNESS)));
        assertTrue(ShadowOutputValidator.isValid(request, output));
    }

    @Test
    void rejectsDuplicatesUnknownMissingAndNullConfidence() {
        ShadowRankingRequest request = request("c0", "c1");
        assertFalse(ShadowOutputValidator.isValid(
                request,
                new ShadowRankingOutput(
                        ShadowRankingConstants.OUTPUT_SCHEMA_VERSION,
                        List.of("c0", "c0"),
                        ShadowConfidence.LOW,
                        Map.of())));
        assertFalse(ShadowOutputValidator.isValid(
                request,
                new ShadowRankingOutput(
                        ShadowRankingConstants.OUTPUT_SCHEMA_VERSION,
                        List.of("c0", "c9"),
                        ShadowConfidence.LOW,
                        Map.of())));
        assertFalse(ShadowOutputValidator.isValid(
                request,
                new ShadowRankingOutput(
                        ShadowRankingConstants.OUTPUT_SCHEMA_VERSION,
                        List.of("c0"),
                        ShadowConfidence.LOW,
                        Map.of())));
        assertFalse(ShadowOutputValidator.isValid(request, null));
    }

    private static ShadowRankingRequest request(String... aliases) {
        List<ShadowCandidateFeatures> features = java.util.stream.IntStream.range(0, aliases.length)
                .mapToObj(i -> new ShadowCandidateFeatures(
                        i,
                        aliases[i],
                        "MUNICIPAL_FACILITY",
                        "0_200",
                        0.1,
                        "LIVE",
                        "HIGH",
                        "HIGH",
                        "11_50",
                        "75_100",
                        false,
                        List.of(),
                        "50_75",
                        i))
                .toList();
        return new ShadowRankingRequest(
                ShadowRankingConstants.REQUEST_SCHEMA_VERSION,
                ShadowRankingConstants.FEATURE_SCHEMA_VERSION,
                features.size(),
                "500_1200",
                ShadowInventoryComposition.MUNICIPAL_ONLY,
                false,
                features);
    }
}
