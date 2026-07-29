package com.parkio.parking.availability.normalization;

import com.parkio.parking.availability.evidence.AvailabilityEvidence;
import java.util.Objects;

/**
 * Maps parking aggregate snapshots into availability evidence.
 */
public final class AvailabilityEvidenceFactory {

    private AvailabilityEvidenceFactory() {}

    public static AvailabilityEvidence fromContext(ParkingSpotAvailabilityContext context) {
        Objects.requireNonNull(context, "context");
        return new AvailabilityEvidence(
                context.parkingSpotId(),
                context.status(),
                context.legalStatus(),
                context.createdAt(),
                context.activatedAt(),
                context.expiresAt(),
                context.verificationCount(),
                context.filledReportCount(),
                context.confidenceScore());
    }
}
