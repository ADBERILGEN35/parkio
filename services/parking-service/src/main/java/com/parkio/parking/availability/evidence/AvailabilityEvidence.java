package com.parkio.parking.availability.evidence;

import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable occupancy-oriented evidence snapshot for one evaluation.
 *
 * <p>Built from aggregate fields only; the engine never loads persistence.
 */
public record AvailabilityEvidence(
        UUID parkingSpotId,
        ParkingSpotStatus status,
        LegalStatus legalStatus,
        Instant createdAt,
        Instant activatedAt,
        Instant expiresAt,
        int verificationCount,
        int filledReportCount,
        double confidenceScore) {

    public AvailabilityEvidence {
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(legalStatus, "legalStatus");
        Objects.requireNonNull(createdAt, "createdAt");
        if (verificationCount < 0) {
            throw new IllegalArgumentException("verificationCount must be non-negative");
        }
        if (filledReportCount < 0) {
            throw new IllegalArgumentException("filledReportCount must be non-negative");
        }
        if (confidenceScore < 0.0 || confidenceScore > 1.0) {
            throw new IllegalArgumentException("confidenceScore must be between 0.0 and 1.0");
        }
    }

    public boolean isPublished() {
        return activatedAt != null;
    }

    public boolean isPendingModeration() {
        return status.isPendingModeration();
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public boolean isTimeExpired(Instant evaluatedAt) {
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        return !status.isPendingModeration() && expiresAt != null && !evaluatedAt.isBefore(expiresAt);
    }
}
