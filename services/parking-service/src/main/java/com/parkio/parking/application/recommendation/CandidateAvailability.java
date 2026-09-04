package com.parkio.parking.application.recommendation;

import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import java.time.Instant;

/**
 * Typed per-channel availability. Community and municipal semantics differ —
 * this is a discriminated view, not a fused occupancy model.
 */
public record CandidateAvailability(
        Kind kind,
        MunicipalOccupancyFreshness freshness,
        Integer availableSpaces,
        Integer occupiedSpaces,
        Integer capacityTotal,
        String sourceLabel,
        Instant observationTimestamp,
        String communityStatus,
        Instant expiresAt) {

    public enum Kind {
        MUNICIPAL,
        COMMUNITY
    }

    public static CandidateAvailability municipal(
            MunicipalOccupancyFreshness freshness,
            Integer availableSpaces,
            Integer occupiedSpaces,
            Integer capacityTotal,
            String sourceLabel,
            Instant observationTimestamp) {
        return new CandidateAvailability(
                Kind.MUNICIPAL,
                freshness,
                availableSpaces,
                occupiedSpaces,
                capacityTotal,
                sourceLabel,
                observationTimestamp,
                null,
                null);
    }

    public static CandidateAvailability community(String status, Instant expiresAt) {
        return new CandidateAvailability(
                Kind.COMMUNITY,
                null,
                null,
                null,
                null,
                null,
                null,
                status,
                expiresAt);
    }
}
