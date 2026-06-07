package com.parkio.parking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A single user verification/report recorded against a spot. */
public final class ParkingSpotVerification {

    private final UUID id;
    private final UUID spotId;
    private final UUID verifierUserId;
    private final VerificationResult result;
    private final Instant createdAt;

    public ParkingSpotVerification(UUID id, UUID spotId, UUID verifierUserId,
                                   VerificationResult result, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.spotId = Objects.requireNonNull(spotId, "spotId");
        this.verifierUserId = Objects.requireNonNull(verifierUserId, "verifierUserId");
        this.result = Objects.requireNonNull(result, "result");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ParkingSpotVerification record(UUID spotId, UUID verifierUserId,
                                                 VerificationResult result, Instant now) {
        return new ParkingSpotVerification(UUID.randomUUID(), spotId, verifierUserId, result, now);
    }

    public UUID id() {
        return id;
    }

    public UUID spotId() {
        return spotId;
    }

    public UUID verifierUserId() {
        return verifierUserId;
    }

    public VerificationResult result() {
        return result;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
