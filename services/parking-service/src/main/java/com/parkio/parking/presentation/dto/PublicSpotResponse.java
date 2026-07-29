package com.parkio.parking.presentation.dto;

import com.parkio.parking.domain.ParkingSpot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Privacy-safe spot view for non-owner viewers (public detail and nearby search).
 * Deliberately omits {@code ownerUserId} and the internal moderation signals
 * ({@code confidenceScore}, {@code verificationCount}, {@code filledReportCount}).
 * Owners get the full {@link SpotResponse} via the {@code /my-spots} endpoints.
 *
 * <p>{@code rejection} is additive and only present when structured rejection metadata exists.
 */
public record PublicSpotResponse(
        UUID id,
        UUID mediaId,
        double latitude,
        double longitude,
        String addressText,
        String description,
        boolean manualLocationEdited,
        List<String> suitableVehicleTypes,
        String parkingContext,
        String legalStatus,
        List<String> violationReasons,
        String status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        SpotRejectionResponse rejection) {

    public PublicSpotResponse(
            UUID id,
            UUID mediaId,
            double latitude,
            double longitude,
            String addressText,
            String description,
            boolean manualLocationEdited,
            List<String> suitableVehicleTypes,
            String parkingContext,
            String legalStatus,
            List<String> violationReasons,
            String status,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt) {
        this(id, mediaId, latitude, longitude, addressText, description, manualLocationEdited,
                suitableVehicleTypes, parkingContext, legalStatus, violationReasons, status,
                expiresAt, createdAt, updatedAt, null);
    }

    public static PublicSpotResponse from(ParkingSpot s) {
        SpotRejectionResponse rejection = s.rejection() == null
                ? null
                : SpotRejectionResponse.from(s.rejection());
        return new PublicSpotResponse(
                s.id(), s.mediaId(), s.latitude(), s.longitude(), s.addressText(), s.description(),
                s.manualLocationEdited(), s.suitableVehicleTypes().stream().map(Enum::name).toList(),
                s.parkingContext().name(), s.legalStatus().name(),
                s.violationReasons().stream().map(Enum::name).toList(),
                s.status().name(), s.expiresAt(), s.createdAt(), s.updatedAt(), rejection);
    }
}
