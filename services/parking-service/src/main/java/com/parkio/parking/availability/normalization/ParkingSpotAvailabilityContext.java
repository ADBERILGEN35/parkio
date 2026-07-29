package com.parkio.parking.availability.normalization;

import com.parkio.parking.availability.evidence.AvailabilityEvidence;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Plain value object mirroring aggregate fields needed for availability evaluation.
 *
 * <p>Allows application/infrastructure to pass snapshots without coupling the engine to JPA.
 */
public record ParkingSpotAvailabilityContext(
        UUID parkingSpotId,
        ParkingSpotStatus status,
        LegalStatus legalStatus,
        Instant createdAt,
        Instant activatedAt,
        Instant expiresAt,
        int verificationCount,
        int filledReportCount,
        double confidenceScore) {

    public ParkingSpotAvailabilityContext {
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(legalStatus, "legalStatus");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ParkingSpotAvailabilityContext from(ParkingSpot spot) {
        Objects.requireNonNull(spot, "spot");
        return new ParkingSpotAvailabilityContext(
                spot.id(),
                spot.status(),
                spot.legalStatus(),
                spot.createdAt(),
                spot.activatedAt(),
                spot.expiresAt(),
                spot.verificationCount(),
                spot.filledReportCount(),
                spot.confidenceScore());
    }
}
