package com.parkio.user.infrastructure.persistence.entity;

import com.parkio.user.domain.place.FavouriteParkingTargetKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "favourite_parking")
public class FavouriteParkingEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_profile_id", nullable = false, updatable = false)
    private UUID userProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, updatable = false, length = 32)
    private FavouriteParkingTargetKind targetKind;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected FavouriteParkingEntity() {
    }

    public FavouriteParkingEntity(
            UUID id,
            UUID userProfileId,
            FavouriteParkingTargetKind targetKind,
            UUID targetId,
            Instant createdAt,
            Long version) {
        this.id = id;
        this.userProfileId = userProfileId;
        this.targetKind = targetKind;
        this.targetId = targetId;
        this.createdAt = createdAt;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserProfileId() {
        return userProfileId;
    }

    public FavouriteParkingTargetKind getTargetKind() {
        return targetKind;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }
}
