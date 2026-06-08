package com.parkio.gamification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for a user's gamification progress: lifetime points and current
 * level. {@code userId} is the platform-wide authUserId. Points never go below
 * zero. Pure domain: no framework dependencies.
 */
public final class UserLevelProgress {

    public static final long INITIAL_POINTS = 0;
    public static final int INITIAL_LEVEL = 1;

    private final UUID userId;
    private long totalPoints;
    private int currentLevel;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Long version;

    public UserLevelProgress(UUID userId, long totalPoints, int currentLevel,
                             Instant createdAt, Instant updatedAt, Long version) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.totalPoints = totalPoints;
        this.currentLevel = currentLevel;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** A brand-new progress: zero points, level 1. */
    public static UserLevelProgress createDefault(UUID userId, Instant now) {
        return new UserLevelProgress(userId, INITIAL_POINTS, INITIAL_LEVEL, now, now, null);
    }

    /**
     * Applies a signed point delta (clamped so totalPoints never goes below 0) and
     * recomputes the current level from the rule set.
     */
    public void applyPoints(long delta, LevelRuleSet rules, Instant now) {
        this.totalPoints = Math.max(0, this.totalPoints + delta);
        this.currentLevel = rules.levelFor(this.totalPoints);
        this.updatedAt = now;
    }

    public UUID userId() {
        return userId;
    }

    public long totalPoints() {
        return totalPoints;
    }

    public int currentLevel() {
        return currentLevel;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Long version() {
        return version;
    }
}
