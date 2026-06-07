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
        Instant updatedAt) {

    public static PublicSpotResponse from(ParkingSpot s) {
        return new PublicSpotResponse(
                s.id(), s.mediaId(), s.latitude(), s.longitude(), s.addressText(), s.description(),
                s.manualLocationEdited(), s.suitableVehicleTypes().stream().map(Enum::name).toList(),
                s.parkingContext().name(), s.legalStatus().name(),
                s.violationReasons().stream().map(Enum::name).toList(),
                s.status().name(), s.expiresAt(), s.createdAt(), s.updatedAt());
    }
}
