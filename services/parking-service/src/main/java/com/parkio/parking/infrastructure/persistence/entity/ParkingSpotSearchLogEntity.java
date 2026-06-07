package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code parking_spot_search_logs}. */
@Entity
@Table(name = "parking_spot_search_logs")
public class ParkingSpotSearchLogEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "searcher_user_id", nullable = false, updatable = false)
    private UUID searcherUserId;

    @Column(name = "latitude", nullable = false, updatable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false, updatable = false)
    private double longitude;

    @Column(name = "radius_meters", nullable = false, updatable = false)
    private double radiusMeters;

    @Column(name = "result_count", nullable = false, updatable = false)
    private int resultCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ParkingSpotSearchLogEntity() {
        // for JPA
    }

    public ParkingSpotSearchLogEntity(UUID id, UUID searcherUserId, double latitude, double longitude,
                                      double radiusMeters, int resultCount, Instant createdAt) {
        this.id = id;
        this.searcherUserId = searcherUserId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusMeters = radiusMeters;
        this.resultCount = resultCount;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSearcherUserId() {
        return searcherUserId;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public double getRadiusMeters() {
        return radiusMeters;
    }

    public int getResultCount() {
        return resultCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
