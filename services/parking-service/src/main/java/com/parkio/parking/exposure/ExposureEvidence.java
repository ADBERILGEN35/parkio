package com.parkio.parking.exposure;

import java.time.Instant;
import java.util.Objects;

/** Frozen canonical exposure input for one published search candidate. */
public record ExposureEvidence(
        ExposureCandidateId candidateId,
        int distanceMeters,
        int requestRadiusMeters,
        ExposurePublicationQuality publicationQuality,
        ExposureAvailabilityState availabilityState,
        ExposureVehicleMatch vehicleMatch,
        ExposureTrustLevel trustLevel,
        Instant publishedAt,
        Instant activatedAt,
        Instant expiresAt,
        String freshnessBand,
        String distanceBand,
        boolean searchableVisible) {

    public ExposureEvidence {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(publicationQuality, "publicationQuality");
        Objects.requireNonNull(availabilityState, "availabilityState");
        Objects.requireNonNull(vehicleMatch, "vehicleMatch");
        Objects.requireNonNull(trustLevel, "trustLevel");
        Objects.requireNonNull(freshnessBand, "freshnessBand");
        Objects.requireNonNull(distanceBand, "distanceBand");
        if (distanceMeters < 0) {
            throw new IllegalArgumentException("distanceMeters must be non-negative");
        }
        if (requestRadiusMeters <= 0) {
            throw new IllegalArgumentException("requestRadiusMeters must be positive");
        }
    }
}
