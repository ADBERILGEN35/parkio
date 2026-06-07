package com.parkio.parking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Records a nearby-search request and how many results it returned. */
public final class ParkingSpotSearchLog {

    private final UUID id;
    private final UUID searcherUserId;
    private final double latitude;
    private final double longitude;
    private final double radiusMeters;
    private final int resultCount;
    private final Instant createdAt;

    public ParkingSpotSearchLog(UUID id, UUID searcherUserId, double latitude, double longitude,
                                double radiusMeters, int resultCount, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.searcherUserId = Objects.requireNonNull(searcherUserId, "searcherUserId");
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusMeters = radiusMeters;
        this.resultCount = resultCount;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ParkingSpotSearchLog record(UUID searcherUserId, double latitude, double longitude,
                                              double radiusMeters, int resultCount, Instant now) {
        return new ParkingSpotSearchLog(UUID.randomUUID(), searcherUserId, latitude, longitude,
                radiusMeters, resultCount, now);
    }

    public UUID id() {
        return id;
    }

    public UUID searcherUserId() {
        return searcherUserId;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    public double radiusMeters() {
        return radiusMeters;
    }

    public int resultCount() {
        return resultCount;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
