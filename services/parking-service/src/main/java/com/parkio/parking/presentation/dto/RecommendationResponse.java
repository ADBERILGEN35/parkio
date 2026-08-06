package com.parkio.parking.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.parkio.parking.application.recommendation.CandidateAvailability;
import com.parkio.parking.application.recommendation.InventoryChannelStatus;
import com.parkio.parking.application.recommendation.ParkingCandidate;
import com.parkio.parking.application.recommendation.ParkingCandidateChannel;
import com.parkio.parking.application.recommendation.RecommendationReason;
import com.parkio.parking.application.recommendation.RecommendationReasonCode;
import com.parkio.parking.application.recommendation.RecommendationResult;
import com.parkio.parking.application.recommendation.ranking.CandidateScoreBreakdown;
import com.parkio.parking.application.recommendation.ranking.RankingStatus;
import com.parkio.parking.application.recommendation.ranking.RankingVersion;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecommendationResponse(
        DestinationResponse destination,
        Instant generatedAt,
        boolean partial,
        InventoryStatusResponse inventoryStatus,
        List<ParkingCandidateResponse> candidates,
        List<RecommendationReasonResponse> warnings,
        RankingVersion rankingVersion,
        RankingStatus rankingStatus) {

    public static RecommendationResponse from(RecommendationResult result) {
        return new RecommendationResponse(
                DestinationResponse.from(result.destination()),
                result.generatedAt(),
                result.partial(),
                new InventoryStatusResponse(
                        result.communityStatus(),
                        result.municipalStatus()),
                result.candidates().stream().map(ParkingCandidateResponse::from).toList(),
                result.warnings().isEmpty()
                        ? null
                        : result.warnings().stream().map(RecommendationReasonResponse::from).toList(),
                result.rankingVersion(),
                result.rankingStatus());
    }

    public record InventoryStatusResponse(
            InventoryChannelStatus community,
            InventoryChannelStatus municipal) {}

    public record ParkingCandidateResponse(
            String id,
            ParkingCandidateChannel channel,
            String refId,
            String title,
            double latitude,
            double longitude,
            int distanceMeters,
            CandidateAvailabilityResponse availability,
            String sourceLabel,
            int baselineOrder,
            List<RecommendationReasonResponse> reasons,
            Double score,
            ScoreBreakdownResponse scoreBreakdown,
            String rankingVersion) {

        static ParkingCandidateResponse from(ParkingCandidate candidate) {
            return new ParkingCandidateResponse(
                    candidate.id(),
                    candidate.channel(),
                    candidate.refId(),
                    candidate.title(),
                    candidate.latitude(),
                    candidate.longitude(),
                    candidate.distanceMeters(),
                    CandidateAvailabilityResponse.from(candidate.availability()),
                    candidate.sourceLabel(),
                    candidate.baselineOrder(),
                    candidate.reasons().stream().map(RecommendationReasonResponse::from).toList(),
                    candidate.score(),
                    ScoreBreakdownResponse.from(candidate.scoreBreakdown()),
                    candidate.rankingVersion());
        }
    }

    public record ScoreBreakdownResponse(
            double distance,
            double freshness,
            double capacity,
            double confidence,
            double favourite) {

        static ScoreBreakdownResponse from(CandidateScoreBreakdown breakdown) {
            if (breakdown == null) {
                return null;
            }
            return new ScoreBreakdownResponse(
                    breakdown.distance(),
                    breakdown.freshness(),
                    breakdown.capacity(),
                    breakdown.confidence(),
                    breakdown.favourite());
        }
    }

    public record CandidateAvailabilityResponse(
            CandidateAvailability.Kind kind,
            MunicipalOccupancyFreshness freshness,
            Integer availableSpaces,
            Integer occupiedSpaces,
            Integer capacityTotal,
            String sourceLabel,
            Instant observationTimestamp,
            String communityStatus,
            Instant expiresAt) {

        static CandidateAvailabilityResponse from(CandidateAvailability availability) {
            if (availability == null) {
                return null;
            }
            return new CandidateAvailabilityResponse(
                    availability.kind(),
                    availability.freshness(),
                    availability.availableSpaces(),
                    availability.occupiedSpaces(),
                    availability.capacityTotal(),
                    availability.sourceLabel(),
                    availability.observationTimestamp(),
                    availability.communityStatus(),
                    availability.expiresAt());
        }
    }

    public record RecommendationReasonResponse(
            RecommendationReasonCode code,
            Map<String, Object> parameters,
            String messageKey) {

        static RecommendationReasonResponse from(RecommendationReason reason) {
            return new RecommendationReasonResponse(
                    reason.code(),
                    reason.parameters().isEmpty() ? null : reason.parameters(),
                    reason.messageKey());
        }
    }
}
