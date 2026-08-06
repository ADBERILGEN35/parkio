package com.parkio.parking.application.recommendation;

import java.util.List;
import java.util.Objects;

/**
 * Computed provider-neutral parking recommendation view. Not persisted.
 *
 * <p>Identity is namespaced ({@code community:{id}} / {@code municipal:{id}});
 * channels are never fused into one parking entity.
 */
public record ParkingCandidate(
        String id,
        ParkingCandidateChannel channel,
        String refId,
        String title,
        double latitude,
        double longitude,
        int distanceMeters,
        CandidateAvailability availability,
        String sourceLabel,
        int baselineOrder,
        List<RecommendationReason> reasons) {

    public static final int HIGH_CAPACITY_THRESHOLD = 50;

    public ParkingCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(refId, "refId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(reasons, "reasons");
        if (distanceMeters < 0) {
            throw new IllegalArgumentException("distanceMeters must be non-negative");
        }
        if (baselineOrder < 0) {
            throw new IllegalArgumentException("baselineOrder must be non-negative");
        }
        reasons = List.copyOf(reasons);
    }

    public static String communityId(String spotId) {
        return "community:" + spotId;
    }

    public static String municipalId(String facilityId) {
        return "municipal:" + facilityId;
    }
}
