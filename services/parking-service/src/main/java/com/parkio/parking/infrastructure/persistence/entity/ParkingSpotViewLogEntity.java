package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code parking_spot_view_logs}. */
@Entity
@Table(name = "parking_spot_view_logs")
public class ParkingSpotViewLogEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "spot_id", nullable = false, updatable = false)
    private UUID spotId;

    @Column(name = "viewer_user_id", nullable = false, updatable = false)
    private UUID viewerUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ParkingSpotViewLogEntity() {
        // for JPA
    }

    public ParkingSpotViewLogEntity(UUID id, UUID spotId, UUID viewerUserId, Instant createdAt) {
        this.id = id;
        this.spotId = spotId;
        this.viewerUserId = viewerUserId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSpotId() {
        return spotId;
    }

    public UUID getViewerUserId() {
        return viewerUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
