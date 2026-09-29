package com.parkio.user.domain.place;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * User-owned recently used parking reference (WP-SPA-07).
 *
 * <p>Stores a typed target reference only — never copies availability, capacity,
 * coordinates, or source metadata. Written only after an explicit parking-use
 * action via the recording API (not impressions or detail opens).
 */
public final class RecentParking {

    public static final int DEFAULT_MAX_PER_USER = 20;

    private final UUID id;
    private final UUID userProfileId;
    private final RecentParkingTargetKind targetKind;
    private final UUID targetId;
    private final Instant firstUsedAt;
    private Instant lastUsedAt;
    private long useCount;
    private final Long version;

    public RecentParking(
            UUID id,
            UUID userProfileId,
            RecentParkingTargetKind targetKind,
            UUID targetId,
            Instant firstUsedAt,
            Instant lastUsedAt,
            long useCount,
            Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userProfileId = Objects.requireNonNull(userProfileId, "userProfileId");
        this.targetKind = Objects.requireNonNull(targetKind, "targetKind");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.firstUsedAt = Objects.requireNonNull(firstUsedAt, "firstUsedAt");
        this.lastUsedAt = Objects.requireNonNull(lastUsedAt, "lastUsedAt");
        if (useCount < 1) {
            throw new IllegalArgumentException("useCount must be >= 1");
        }
        this.useCount = useCount;
        this.version = version;
    }

    public static RecentParking create(
            UUID userProfileId,
            RecentParkingTargetKind targetKind,
            UUID targetId,
            Instant now) {
        if (targetKind != RecentParkingTargetKind.MUNICIPAL_FACILITY) {
            throw new IllegalArgumentException("unsupported recent parking target kind");
        }
        return new RecentParking(
                UUID.randomUUID(), userProfileId, targetKind, targetId, now, now, 1L, null);
    }

    public void recordUse(Instant now) {
        this.lastUsedAt = Objects.requireNonNull(now, "now");
        this.useCount += 1;
    }

    public UUID id() {
        return id;
    }

    public UUID userProfileId() {
        return userProfileId;
    }

    public RecentParkingTargetKind targetKind() {
        return targetKind;
    }

    public UUID targetId() {
        return targetId;
    }

    public Instant firstUsedAt() {
        return firstUsedAt;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public long useCount() {
        return useCount;
    }

    public Long version() {
        return version;
    }
}
