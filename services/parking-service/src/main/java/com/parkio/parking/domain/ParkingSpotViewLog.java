package com.parkio.parking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Records that a user opened a spot's detail view. */
public final class ParkingSpotViewLog {

    private final UUID id;
    private final UUID spotId;
    private final UUID viewerUserId;
    private final Instant createdAt;

    public ParkingSpotViewLog(UUID id, UUID spotId, UUID viewerUserId, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.spotId = Objects.requireNonNull(spotId, "spotId");
        this.viewerUserId = Objects.requireNonNull(viewerUserId, "viewerUserId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ParkingSpotViewLog record(UUID spotId, UUID viewerUserId, Instant now) {
        return new ParkingSpotViewLog(UUID.randomUUID(), spotId, viewerUserId, now);
    }

    public UUID id() {
        return id;
    }

    public UUID spotId() {
        return spotId;
    }

    public UUID viewerUserId() {
        return viewerUserId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
