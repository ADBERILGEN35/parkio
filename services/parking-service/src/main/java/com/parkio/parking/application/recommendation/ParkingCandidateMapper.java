package com.parkio.parking.application.recommendation;

import com.parkio.parking.application.MunicipalFacilityQueryService.FacilityView;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Maps inventory rows to provider-neutral {@link ParkingCandidate} views. */
public final class ParkingCandidateMapper {

    private ParkingCandidateMapper() {}

    public static ParkingCandidate fromCommunity(
            ParkingSpot spot, double destinationLat, double destinationLng) {
        String refId = spot.id().toString();
        int distance = RecommendationDistances.meters(
                destinationLat, destinationLng, spot.latitude(), spot.longitude());
        List<RecommendationReason> reasons = new ArrayList<>();
        reasons.add(RecommendationReason.of(RecommendationReasonCode.CLOSE_TO_DESTINATION));
        reasons.add(RecommendationReason.of(RecommendationReasonCode.COMMUNITY_FRESH));
        return new ParkingCandidate(
                ParkingCandidate.communityId(refId),
                ParkingCandidateChannel.COMMUNITY_SPOT,
                refId,
                communityTitle(spot),
                spot.latitude(),
                spot.longitude(),
                distance,
                CandidateAvailability.community(spot.status().name(), spot.expiresAt()),
                null,
                0,
                reasons);
    }

    public static ParkingCandidate fromMunicipal(
            FacilityView facility, double destinationLat, double destinationLng) {
        String refId = facility.id().toString();
        int distance = RecommendationDistances.meters(
                destinationLat, destinationLng, facility.latitude(), facility.longitude());
        List<RecommendationReason> reasons = new ArrayList<>();
        reasons.add(RecommendationReason.of(RecommendationReasonCode.CLOSE_TO_DESTINATION));

        MunicipalOccupancyFreshness freshness = facility.freshness();
        if (freshness == MunicipalOccupancyFreshness.LIVE && facility.availableSpaces() != null) {
            reasons.add(RecommendationReason.of(RecommendationReasonCode.LIVE_AVAILABILITY));
        }
        if (freshness == MunicipalOccupancyFreshness.UNAVAILABLE
                || freshness == MunicipalOccupancyFreshness.STALE) {
            reasons.add(RecommendationReason.of(RecommendationReasonCode.STATIC_INVENTORY));
        }
        if (facility.capacityTotal() != null
                && facility.capacityTotal() >= ParkingCandidate.HIGH_CAPACITY_THRESHOLD) {
            reasons.add(RecommendationReason.of(
                    RecommendationReasonCode.HIGH_CAPACITY,
                    java.util.Map.of("capacityTotal", facility.capacityTotal())));
        }

        return new ParkingCandidate(
                ParkingCandidate.municipalId(refId),
                ParkingCandidateChannel.MUNICIPAL_FACILITY,
                refId,
                municipalTitle(facility),
                facility.latitude(),
                facility.longitude(),
                distance,
                CandidateAvailability.municipal(
                        freshness,
                        facility.availableSpaces(),
                        facility.occupiedSpaces(),
                        facility.capacityTotal(),
                        facility.sourceLabel(),
                        facility.lastUpdatedAt()),
                facility.sourceLabel(),
                0,
                reasons);
    }

    public static ParkingCandidate withBaselineOrder(ParkingCandidate candidate, int order) {
        return new ParkingCandidate(
                candidate.id(),
                candidate.channel(),
                candidate.refId(),
                candidate.title(),
                candidate.latitude(),
                candidate.longitude(),
                candidate.distanceMeters(),
                candidate.availability(),
                candidate.sourceLabel(),
                order,
                candidate.reasons());
    }

    private static String communityTitle(ParkingSpot spot) {
        if (spot.addressText() != null && !spot.addressText().isBlank()) {
            return spot.addressText().trim();
        }
        if (spot.description() != null && !spot.description().isBlank()) {
            String description = spot.description().trim();
            return description.length() > 120 ? description.substring(0, 120) : description;
        }
        return "Community parking";
    }

    private static String municipalTitle(FacilityView facility) {
        if (facility.displayName() != null && !facility.displayName().isBlank()) {
            return facility.displayName().trim();
        }
        if (facility.operatorName() != null && !facility.operatorName().isBlank()) {
            return facility.operatorName().trim();
        }
        if (facility.facilityType() != null) {
            return facility.facilityType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        return "Municipal facility";
    }
}
