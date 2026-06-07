package com.parkio.parking.presentation.dto;

import com.parkio.parking.domain.ParkingSpot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Spot representation for API responses. Holds only domain data — no storage
 * internals exist in this service. Enum values are emitted as their names.
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
        Instant updatedAt) {

    public static SpotResponse from(ParkingSpot s) {
        return new SpotResponse(
                s.id(), s.ownerUserId(), s.mediaId(), s.latitude(), s.longitude(),
                s.addressText(), s.description(), s.manualLocationEdited(),
                s.suitableVehicleTypes().stream().map(Enum::name).toList(),
                s.parkingContext().name(), s.legalStatus().name(),
                s.violationReasons().stream().map(Enum::name).toList(),
                s.status().name(), s.confidenceScore(), s.verificationCount(), s.filledReportCount(),
                s.expiresAt(), s.createdAt(), s.updatedAt());
    }
}
