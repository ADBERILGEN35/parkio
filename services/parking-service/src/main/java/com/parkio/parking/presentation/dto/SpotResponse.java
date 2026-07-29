package com.parkio.parking.presentation.dto;

import com.parkio.parking.domain.ParkingSpot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Spot representation for API responses. Holds only domain data — no storage
 * internals exist in this service. Enum values are emitted as their names.
 *
 * <p>{@code rejection} is additive and null unless structured rejection metadata exists.
 */
public record SpotResponse(
        UUID id,
        UUID ownerUserId,
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
        double confidenceScore,
        int verificationCount,
        int filledReportCount,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        SpotRejectionResponse rejection) {

    public SpotResponse(
            UUID id,
            UUID ownerUserId,
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
            double confidenceScore,
            int verificationCount,
            int filledReportCount,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt) {
        this(id, ownerUserId, mediaId, latitude, longitude, addressText, description, manualLocationEdited,
                suitableVehicleTypes, parkingContext, legalStatus, violationReasons, status, confidenceScore,
                verificationCount, filledReportCount, expiresAt, createdAt, updatedAt, null);
    }

    public static SpotResponse from(ParkingSpot s) {
        SpotRejectionResponse rejection = null;
        if (s.rejection() != null) {
            rejection = SpotRejectionResponse.from(s.rejection());
        }
        return new SpotResponse(
                s.id(), s.ownerUserId(), s.mediaId(), s.latitude(), s.longitude(),
                s.addressText(), s.description(), s.manualLocationEdited(),
                s.suitableVehicleTypes().stream().map(Enum::name).toList(),
                s.parkingContext().name(), s.legalStatus().name(),
                s.violationReasons().stream().map(Enum::name).toList(),
                s.status().name(), s.confidenceScore(), s.verificationCount(), s.filledReportCount(),
                s.expiresAt(), s.createdAt(), s.updatedAt(), rejection);
    }
}
