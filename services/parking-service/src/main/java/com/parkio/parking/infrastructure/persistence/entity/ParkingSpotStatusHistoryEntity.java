package com.parkio.parking.infrastructure.persistence.entity;

import com.parkio.parking.domain.ParkingSpotStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code parking_spot_status_history}. */
@Entity
@Table(name = "parking_spot_status_history")
public class ParkingSpotStatusHistoryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "spot_id", nullable = false, updatable = false)
    private UUID spotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", updatable = false)
    private ParkingSpotStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, updatable = false)
    private ParkingSpotStatus newStatus;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ParkingSpotStatusHistoryEntity() {
        // for JPA
    }

    public ParkingSpotStatusHistoryEntity(UUID id, UUID spotId, ParkingSpotStatus previousStatus,
                                          ParkingSpotStatus newStatus, String reason, Instant createdAt) {
        this.id = id;
        this.spotId = spotId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSpotId() {
        return spotId;
    }

    public ParkingSpotStatus getPreviousStatus() {
        return previousStatus;
    }

    public ParkingSpotStatus getNewStatus() {
        return newStatus;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
