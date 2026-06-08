package com.parkio.gamification.infrastructure.persistence.entity;

import com.parkio.gamification.domain.PointDirection;
import com.parkio.gamification.domain.PointSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code point_transactions} (the immutable point ledger). */
@Entity
@Table(name = "point_transactions")
public class PointTransactionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false)
    private PointSourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false)
    private PointDirection direction;

    @Column(name = "points", nullable = false, updatable = false)
    private long points;

    @Column(name = "related_event_id", updatable = false)
    private UUID relatedEventId;

    @Column(name = "related_spot_id", updatable = false)
    private UUID relatedSpotId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PointTransactionEntity() {
        // for JPA
    }

    public PointTransactionEntity(UUID id, UUID userId, String idempotencyKey, PointSourceType sourceType,
                                  PointDirection direction, long points, UUID relatedEventId,
                                  UUID relatedSpotId, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.sourceType = sourceType;
        this.direction = direction;
        this.points = points;
        this.relatedEventId = relatedEventId;
        this.relatedSpotId = relatedSpotId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public PointSourceType getSourceType() {
        return sourceType;
    }

    public PointDirection getDirection() {
        return direction;
    }

    public long getPoints() {
        return points;
    }

    public UUID getRelatedEventId() {
        return relatedEventId;
    }

    public UUID getRelatedSpotId() {
        return relatedSpotId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
