package com.parkio.parking.application.recommendation.ranking.shadow;

import java.util.List;
import java.util.Objects;

/** Privacy-safe shadow ranking request built from authoritative candidates. */
public record ShadowRankingRequest(
        String schemaVersion,
        String featureSchemaVersion,
        int candidateCount,
        String radiusBucket,
        ShadowInventoryComposition inventoryComposition,
        boolean partial,
        List<ShadowCandidateFeatures> candidates) {

    public ShadowRankingRequest {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(featureSchemaVersion, "featureSchemaVersion");
        Objects.requireNonNull(radiusBucket, "radiusBucket");
        Objects.requireNonNull(inventoryComposition, "inventoryComposition");
        Objects.requireNonNull(candidates, "candidates");
        if (candidateCount < 0) {
            throw new IllegalArgumentException("candidateCount must be non-negative");
        }
        candidates = List.copyOf(candidates);
    }
}
