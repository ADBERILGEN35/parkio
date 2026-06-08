package com.parkio.gamification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A point-in-time contribution score for a user (append-only history). For this
 * foundation {@code score} tracks lifetime points; a future scheduled job will
 * apply rolling-window decay (ai-context/02).
 */
public final class ContributionSnapshot {

    private final UUID id;
    private final UUID userId;
    private final long score;
    private final Instant capturedAt;

    public ContributionSnapshot(UUID id, UUID userId, long score, Instant capturedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.score = score;
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
    }

    public static ContributionSnapshot capture(UUID userId, long score, Instant now) {
        return new ContributionSnapshot(UUID.randomUUID(), userId, score, now);
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public long score() {
        return score;
    }

    public Instant capturedAt() {
        return capturedAt;
    }
}
