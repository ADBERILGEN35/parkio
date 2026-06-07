package com.parkio.parking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only record of a spot status transition. */
public final class ParkingSpotStatusHistory {

    private final UUID id;
    private final UUID spotId;
    private final ParkingSpotStatus previousStatus;
    private final ParkingSpotStatus newStatus;
    private final String reason;
    private final Instant createdAt;

    public ParkingSpotStatusHistory(UUID id, UUID spotId, ParkingSpotStatus previousStatus,
                                    ParkingSpotStatus newStatus, String reason, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.spotId = Objects.requireNonNull(spotId, "spotId");
        this.previousStatus = previousStatus;
        this.newStatus = Objects.requireNonNull(newStatus, "newStatus");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ParkingSpotStatusHistory record(UUID spotId, ParkingSpotStatus previousStatus,
                                                  ParkingSpotStatus newStatus, String reason, Instant now) {
        return new ParkingSpotStatusHistory(UUID.randomUUID(), spotId, previousStatus, newStatus, reason, now);
    }

    public UUID id() {
        return id;
    }

    public UUID spotId() {
        return spotId;
    }

    public ParkingSpotStatus previousStatus() {
        return previousStatus;
    }

    public ParkingSpotStatus newStatus() {
        return newStatus;
    }

    public String reason() {
        return reason;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
