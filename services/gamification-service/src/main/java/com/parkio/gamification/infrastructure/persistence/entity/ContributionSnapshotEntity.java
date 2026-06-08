package com.parkio.gamification.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code contribution_snapshots} (append-only). */
@Entity
@Table(name = "contribution_snapshots")
public class ContributionSnapshotEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "score", nullable = false, updatable = false)
    private long score;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant capturedAt;

    protected ContributionSnapshotEntity() {
        // for JPA
    }

    public ContributionSnapshotEntity(UUID id, UUID userId, long score, Instant capturedAt) {
        this.id = id;
        this.userId = userId;
        this.score = score;
        this.capturedAt = capturedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public long getScore() {
        return score;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }
}
