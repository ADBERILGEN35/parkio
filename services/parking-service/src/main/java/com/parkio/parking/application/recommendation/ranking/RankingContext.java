package com.parkio.parking.application.recommendation.ranking;

import com.parkio.parking.application.recommendation.ParkingCandidate;
import com.parkio.parking.domain.place.Destination;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Inputs for ranking — collected by orchestration, not by the ranker. */
public record RankingContext(
        Destination destination,
        UUID requesterUserId,
        int radiusMeters,
        List<ParkingCandidate> candidates,
        Set<UUID> favouriteFacilityIds,
        RankingProperties.RankingConfiguration config) {

    public RankingContext {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(requesterUserId, "requesterUserId");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(favouriteFacilityIds, "favouriteFacilityIds");
        Objects.requireNonNull(config, "config");
        candidates = List.copyOf(candidates);
        favouriteFacilityIds = Set.copyOf(favouriteFacilityIds);
    }
}
