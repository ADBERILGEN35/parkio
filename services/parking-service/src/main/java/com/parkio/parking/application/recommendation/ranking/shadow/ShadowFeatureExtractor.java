package com.parkio.parking.application.recommendation.ranking.shadow;

import com.parkio.parking.application.recommendation.CandidateAvailability;
import com.parkio.parking.application.recommendation.ParkingCandidate;
import com.parkio.parking.application.recommendation.ParkingCandidateChannel;
import com.parkio.parking.application.recommendation.RecommendationReason;
import com.parkio.parking.application.recommendation.RecommendationReasonCode;
import com.parkio.parking.application.recommendation.ranking.CandidateScoreBreakdown;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds privacy-minimized {@link ShadowRankingRequest} from authoritative
 * (deterministic public order) candidates. Never emits user IDs, coordinates,
 * titles, facility IDs, or ref IDs.
 */
public final class ShadowFeatureExtractor {

    private static final int DEFAULT_DISTANCE_CAP_METERS = 1200;

    private ShadowFeatureExtractor() {}

    public static ShadowRankingRequest extract(
            List<ParkingCandidate> authoritativeOrdered, boolean inventoryPartial, int radiusMeters) {
        Objects.requireNonNull(authoritativeOrdered, "authoritativeOrdered");
        List<ParkingCandidate> candidates = List.copyOf(authoritativeOrdered);
        List<ShadowCandidateFeatures> features = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            features.add(toFeatures(candidates.get(i), i));
        }
        return new ShadowRankingRequest(
                ShadowRankingConstants.REQUEST_SCHEMA_VERSION,
                ShadowRankingConstants.FEATURE_SCHEMA_VERSION,
                features.size(),
                radiusBucket(radiusMeters),
                inventoryComposition(candidates),
                inventoryPartial,
                features);
    }

    public static ShadowCandidateFeatures toFeatures(ParkingCandidate candidate, int deterministicPosition) {
        Objects.requireNonNull(candidate, "candidate");
        String alias = "c" + deterministicPosition;
        CandidateAvailability availability = candidate.availability();
        CandidateScoreBreakdown breakdown = candidate.scoreBreakdown();
        boolean favourite = isFavourite(candidate, breakdown);
        return new ShadowCandidateFeatures(
                deterministicPosition,
                alias,
                candidate.channel().name(),
                distanceBucket(candidate.distanceMeters()),
                distanceNormalized(candidate.distanceMeters(), DEFAULT_DISTANCE_CAP_METERS),
                occupancyFreshnessKind(availability),
                availabilityBucket(availability),
                availabilityRatioBucket(availability),
                capacityBucket(availability),
                inventoryConfidenceBucket(availability, breakdown),
                favourite,
                reasonCodeNames(candidate.reasons()),
                deterministicScoreBucket(candidate.score()),
                deterministicPosition);
    }

    static boolean isFavourite(ParkingCandidate candidate, CandidateScoreBreakdown breakdown) {
        if (breakdown != null && breakdown.favourite() > 0.0) {
            return true;
        }
        for (RecommendationReason reason : candidate.reasons()) {
            if (reason != null && reason.code() == RecommendationReasonCode.FAVOURITE) {
                return true;
            }
        }
        return false;
    }

    static ShadowInventoryComposition inventoryComposition(List<ParkingCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return ShadowInventoryComposition.EMPTY;
        }
        boolean municipal = false;
        boolean community = false;
        for (ParkingCandidate candidate : candidates) {
            if (candidate.channel() == ParkingCandidateChannel.MUNICIPAL_FACILITY) {
                municipal = true;
            } else if (candidate.channel() == ParkingCandidateChannel.COMMUNITY_SPOT) {
                community = true;
            }
        }
        if (municipal && community) {
            return ShadowInventoryComposition.MIXED;
        }
        if (municipal) {
            return ShadowInventoryComposition.MUNICIPAL_ONLY;
        }
        if (community) {
            return ShadowInventoryComposition.COMMUNITY_ONLY;
        }
        return ShadowInventoryComposition.EMPTY;
    }

    static String distanceBucket(int distanceMeters) {
        if (distanceMeters < 200) {
            return "0_200";
        }
        if (distanceMeters < 500) {
            return "200_500";
        }
        if (distanceMeters < 1200) {
            return "500_1200";
        }
        return "1200_plus";
    }

    static double distanceNormalized(int distanceMeters, int capMeters) {
        if (distanceMeters <= 0) {
            return 0.0;
        }
        if (capMeters <= 0) {
            return 1.0;
        }
        return Math.min((double) distanceMeters / (double) capMeters, 1.0);
    }

    static String occupancyFreshnessKind(CandidateAvailability availability) {
        if (availability == null) {
            return "UNKNOWN";
        }
        if (availability.kind() == CandidateAvailability.Kind.COMMUNITY) {
            return "COMMUNITY";
        }
        MunicipalOccupancyFreshness freshness = availability.freshness();
        if (freshness == null) {
            return "UNKNOWN";
        }
        return switch (freshness) {
            case LIVE, AGING -> "LIVE";
            case STALE -> "STALE";
            case UNAVAILABLE, INVALID -> "STATIC";
        };
    }

    static String availabilityBucket(CandidateAvailability availability) {
        if (availability == null || availability.kind() != CandidateAvailability.Kind.MUNICIPAL) {
            return "UNKNOWN";
        }
        Integer available = availability.availableSpaces();
        if (available == null) {
            return "UNKNOWN";
        }
        if (available <= 0) {
            return "ZERO";
        }
        if (available <= 5) {
            return "LOW";
        }
        if (available <= 20) {
            return "MED";
        }
        return "HIGH";
    }

    static String availabilityRatioBucket(CandidateAvailability availability) {
        if (availability == null || availability.kind() != CandidateAvailability.Kind.MUNICIPAL) {
            return "UNKNOWN";
        }
        Integer available = availability.availableSpaces();
        Integer capacity = availability.capacityTotal();
        if (available == null || capacity == null || capacity <= 0) {
            return "UNKNOWN";
        }
        double ratio = Math.min(Math.max(available.doubleValue() / capacity.doubleValue(), 0.0), 1.0);
        if (ratio <= 0.0) {
            return "ZERO";
        }
        if (ratio < 0.25) {
            return "LOW";
        }
        if (ratio < 0.6) {
            return "MED";
        }
        return "HIGH";
    }

    static String capacityBucket(CandidateAvailability availability) {
        if (availability == null || availability.kind() != CandidateAvailability.Kind.MUNICIPAL) {
            return "UNKNOWN";
        }
        Integer capacity = availability.capacityTotal();
        if (capacity == null) {
            return "UNKNOWN";
        }
        if (capacity <= 0) {
            return "0";
        }
        if (capacity <= 10) {
            return "1_10";
        }
        if (capacity <= 50) {
            return "11_50";
        }
        return "51_plus";
    }

    static String inventoryConfidenceBucket(
            CandidateAvailability availability, CandidateScoreBreakdown breakdown) {
        if (breakdown != null) {
            return scoreBucket(breakdown.confidence());
        }
        if (availability == null) {
            return "UNKNOWN";
        }
        if (availability.kind() == CandidateAvailability.Kind.COMMUNITY) {
            return "LOW";
        }
        MunicipalOccupancyFreshness freshness = availability.freshness();
        if (freshness == MunicipalOccupancyFreshness.LIVE) {
            return "HIGH";
        }
        if (freshness == MunicipalOccupancyFreshness.AGING) {
            return "MED";
        }
        if (freshness == MunicipalOccupancyFreshness.STALE) {
            return "LOW";
        }
        return "LOW";
    }

    static String deterministicScoreBucket(Double score) {
        if (score == null || !Double.isFinite(score)) {
            return "UNKNOWN";
        }
        return scoreBucket(score);
    }

    static String scoreBucket(double score) {
        if (!Double.isFinite(score) || score < 0.25) {
            return "0_25";
        }
        if (score < 0.5) {
            return "25_50";
        }
        if (score < 0.75) {
            return "50_75";
        }
        return "75_100";
    }

    static String radiusBucket(int radiusMeters) {
        if (radiusMeters <= 500) {
            return "0_500";
        }
        if (radiusMeters <= 1200) {
            return "500_1200";
        }
        if (radiusMeters <= 2500) {
            return "1200_2500";
        }
        return "2500_plus";
    }

    private static List<String> reasonCodeNames(List<RecommendationReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(reasons.size());
        for (RecommendationReason reason : reasons) {
            if (reason != null && reason.code() != null) {
                names.add(reason.code().name());
            }
        }
        return List.copyOf(names);
    }
}
