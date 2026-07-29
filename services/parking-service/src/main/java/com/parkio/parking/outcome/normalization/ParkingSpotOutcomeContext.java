package com.parkio.parking.outcome.normalization;

import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Plain aggregate snapshot for outcome evidence construction. */
public record ParkingSpotOutcomeContext(
        UUID parkingSpotId,
        ParkingSpotStatus status,
        Instant createdAt,
        Instant activatedAt,
        Instant expiresAt,
        Instant updatedAt,
        int verificationCount,
        int filledReportCount,
        double confidenceScore) {

    public ParkingSpotOutcomeContext {
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ParkingSpotOutcomeContext from(ParkingSpot spot) {
        Objects.requireNonNull(spot, "spot");
        return new ParkingSpotOutcomeContext(
                spot.id(),
                spot.status(),
                spot.createdAt(),
                spot.activatedAt(),
                spot.expiresAt(),
                spot.updatedAt(),
                spot.verificationCount(),
                spot.filledReportCount(),
                spot.confidenceScore());
    }
}