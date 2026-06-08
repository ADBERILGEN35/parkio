package com.parkio.gamification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable ledger entry for a single point change. {@code idempotencyKey} makes
 * applying the same logical award/penalty twice a no-op. {@code points} is always a
 * positive magnitude; {@link #direction} says whether it was added or removed.
 */
public final class PointTransaction {

    private final UUID id;
    private final UUID userId;
    private final String idempotencyKey;
    private final PointSourceType sourceType;
    private final PointDirection direction;
    private final long points;
    private final UUID relatedEventId;
    private final UUID relatedSpotId;
    private final Instant createdAt;

    public PointTransaction(UUID id, UUID userId, String idempotencyKey, PointSourceType sourceType,
                            PointDirection direction, long points, UUID relatedEventId,
                            UUID relatedSpotId, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.direction = Objects.requireNonNull(direction, "direction");
        if (points < 0) {
            throw new IllegalArgumentException("points must be a non-negative magnitude");
        }
        this.points = points;
        this.relatedEventId = relatedEventId;
        this.relatedSpotId = relatedSpotId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static PointTransaction record(UUID userId, String idempotencyKey, PointSourceType sourceType,
                                          PointDirection direction, long points, UUID relatedEventId,
                                          UUID relatedSpotId, Instant now) {
        return new PointTransaction(UUID.randomUUID(), userId, idempotencyKey, sourceType, direction,
                points, relatedEventId, relatedSpotId, now);
    }

    /** Signed effect on the user's total ({@code +points} earned, {@code -points} deducted). */
    public long signedPoints() {
        return direction == PointDirection.EARNED ? points : -points;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public PointSourceType sourceType() {
        return sourceType;
    }

    public PointDirection direction() {
        return direction;
    }

    public long points() {
        return points;
    }

    public UUID relatedEventId() {
        return relatedEventId;
    }

    public UUID relatedSpotId() {
        return relatedSpotId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
