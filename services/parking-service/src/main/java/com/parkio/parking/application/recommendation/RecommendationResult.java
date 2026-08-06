package com.parkio.parking.application.recommendation;

import com.parkio.parking.application.recommendation.ranking.RankingStatus;
import com.parkio.parking.application.recommendation.ranking.RankingVersion;
import com.parkio.parking.domain.place.Destination;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Orchestration result for a recommendation request. */
public record RecommendationResult(
        Destination destination,
        Instant generatedAt,
        boolean partial,
        InventoryChannelStatus communityStatus,
        InventoryChannelStatus municipalStatus,
        List<ParkingCandidate> candidates,
        List<RecommendationReason> warnings,
        RankingVersion rankingVersion,
        RankingStatus rankingStatus) {

    public RecommendationResult {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(communityStatus, "communityStatus");
        Objects.requireNonNull(municipalStatus, "municipalStatus");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(warnings, "warnings");
        Objects.requireNonNull(rankingVersion, "rankingVersion");
        Objects.requireNonNull(rankingStatus, "rankingStatus");
        candidates = List.copyOf(candidates);
        warnings = List.copyOf(warnings);
    }
}
