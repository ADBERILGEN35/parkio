package com.parkio.gamification.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code user_level_progress}. {@code user_id} is the authUserId. */
@Entity
@Table(name = "user_level_progress")
public class UserLevelProgressEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "total_points", nullable = false)
    private long totalPoints;

    @Column(name = "current_level", nullable = false)
    private int currentLevel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected UserLevelProgressEntity() {
        // for JPA
    }

    public UserLevelProgressEntity(UUID userId, long totalPoints, int currentLevel,
                                   Instant createdAt, Instant updatedAt, Long version) {
        this.userId = userId;
        this.totalPoints = totalPoints;
        this.currentLevel = currentLevel;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public UUID getUserId() {
        return userId;
    }

    public long getTotalPoints() {
        return totalPoints;
    }

    public int getCurrentLevel() {
        return currentLevel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
