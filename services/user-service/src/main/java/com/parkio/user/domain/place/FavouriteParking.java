package com.parkio.user.domain.place;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * User-owned bookmark of a parking option (WP-SPA-04).
 *
 * <p>Stores a typed reference only — never copies facility coordinates,
 * occupancy, capacity, or source metadata.
 */
public final class FavouriteParking {

    public static final int MAX_PER_USER = 100;

    private final UUID id;
    private final UUID userProfileId;
    private final FavouriteParkingTargetKind targetKind;
    private final UUID targetId;
    private final Instant createdAt;
    private final Long version;

    public FavouriteParking(
            UUID id,
            UUID userProfileId,
            FavouriteParkingTargetKind targetKind,
            UUID targetId,
            Instant createdAt,
            Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userProfileId = Objects.requireNonNull(userProfileId, "userProfileId");
        this.targetKind = Objects.requireNonNull(targetKind, "targetKind");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
    }

    public static FavouriteParking create(
            UUID userProfileId,
            FavouriteParkingTargetKind targetKind,
            UUID targetId,
            Instant now) {
        if (targetKind != FavouriteParkingTargetKind.MUNICIPAL_FACILITY) {
            throw new IllegalArgumentException("unsupported favourite parking target kind");
        }
        return new FavouriteParking(UUID.randomUUID(), userProfileId, targetKind, targetId, now, null);
    }

    public UUID id() {
        return id;
    }

    public UUID userProfileId() {
        return userProfileId;
    }

    public FavouriteParkingTargetKind targetKind() {
        return targetKind;
    }

    public UUID targetId() {
        return targetId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Long version() {
        return version;
    }
}
