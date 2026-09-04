package com.parkio.parking.application.recommendation;

import com.parkio.parking.application.recommendation.ranking.CandidateScoreBreakdown;
import java.util.List;
import java.util.Objects;

/**
 * Computed provider-neutral parking recommendation view. Not persisted.
 *
 * <p>Identity is namespaced ({@code community:{id}} / {@code municipal:{id}});
 * channels are never fused into one parking entity.
 *
 * <p>Optional ranking fields ({@code score}, {@code scoreBreakdown},
 * {@code rankingVersion}) are additive for WP-SPA-06; null when ranking is
 * disabled or not applied to this candidate.
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
        List<RecommendationReason> reasons,
        Double score,
        CandidateScoreBreakdown scoreBreakdown,
        String rankingVersion) {

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
        if (score != null && (!Double.isFinite(score) || score < 0.0)) {
            throw new IllegalArgumentException("score must be finite and non-negative when present");
        }
        reasons = List.copyOf(reasons);
    }

    /** SPA-05 constructor — no ranking fields. */
    public ParkingCandidate(
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
        this(
                id,
                channel,
                refId,
                title,
                latitude,
                longitude,
                distanceMeters,
                availability,
                sourceLabel,
                baselineOrder,
                reasons,
                null,
                null,
                null);
    }

    public static String communityId(String spotId) {
        return "community:" + spotId;
    }

    public static String municipalId(String facilityId) {
        return "municipal:" + facilityId;
    }
}
