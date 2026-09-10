package com.parkio.parking.application.recommendation.ranking.shadow;

import java.util.List;
import java.util.Objects;

/**
 * Privacy-minimized candidate features for shadow ranking. No user IDs,
 * coordinates, titles, facility IDs, or ref IDs.
 */
public record ShadowCandidateFeatures(
        int candidateOrdinal,
        String alias,
        String channel,
        String distanceBucket,
        double distanceNormalized,
        String occupancyFreshnessKind,
        String availabilityBucket,
        String availabilityRatioBucket,
        String capacityBucket,
        String inventoryConfidenceBucket,
        boolean isFavourite,
        List<String> reasonCodes,
        String deterministicScoreBucket,
        int deterministicPosition) {

    public ShadowCandidateFeatures {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(distanceBucket, "distanceBucket");
        Objects.requireNonNull(occupancyFreshnessKind, "occupancyFreshnessKind");
        Objects.requireNonNull(availabilityBucket, "availabilityBucket");
        Objects.requireNonNull(availabilityRatioBucket, "availabilityRatioBucket");
        Objects.requireNonNull(capacityBucket, "capacityBucket");
        Objects.requireNonNull(inventoryConfidenceBucket, "inventoryConfidenceBucket");
        Objects.requireNonNull(reasonCodes, "reasonCodes");
        Objects.requireNonNull(deterministicScoreBucket, "deterministicScoreBucket");
        if (!Double.isFinite(distanceNormalized)
                || distanceNormalized < 0.0
                || distanceNormalized > 1.0) {
            throw new IllegalArgumentException("distanceNormalized must be finite in [0,1]");
        }
        if (candidateOrdinal < 0 || deterministicPosition < 0) {
            throw new IllegalArgumentException("ordinals must be non-negative");
        }
        reasonCodes = List.copyOf(reasonCodes);
    }
}
