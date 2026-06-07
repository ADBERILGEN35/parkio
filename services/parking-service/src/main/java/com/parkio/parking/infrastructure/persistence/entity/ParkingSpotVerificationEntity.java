package com.parkio.parking.infrastructure.persistence.entity;

import com.parkio.parking.domain.VerificationResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code parking_spot_verifications}. */
@Entity
@Table(name = "parking_spot_verifications")
public class ParkingSpotVerificationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "spot_id", nullable = false, updatable = false)
    private UUID spotId;

    @Column(name = "verifier_user_id", nullable = false, updatable = false)
    private UUID verifierUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, updatable = false)
    private VerificationResult result;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ParkingSpotVerificationEntity() {
        // for JPA
    }

    public ParkingSpotVerificationEntity(UUID id, UUID spotId, UUID verifierUserId,
                                         VerificationResult result, Instant createdAt) {
        this.id = id;
        this.spotId = spotId;
        this.verifierUserId = verifierUserId;
        this.result = result;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSpotId() {
        return spotId;
    }

    public UUID getVerifierUserId() {
        return verifierUserId;
    }

    public VerificationResult getResult() {
        return result;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
