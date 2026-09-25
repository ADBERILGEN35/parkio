package com.parkio.parking.application.recommendation.ranking.shadow;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Challenger output: ordered aliases plus coarse reason categories. */
public record ShadowRankingOutput(
        String schemaVersion,
        List<String> orderedCandidateAliases,
        ShadowConfidence confidence,
        Map<String, List<ShadowReasonCategory>> reasonCategories) {

    public ShadowRankingOutput {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(orderedCandidateAliases, "orderedCandidateAliases");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(reasonCategories, "reasonCategories");
        orderedCandidateAliases = List.copyOf(orderedCandidateAliases);
        reasonCategories = Map.copyOf(reasonCategories);
    }
}
