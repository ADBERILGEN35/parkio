package com.parkio.gamification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for a user's trust score (0-100, ai-context/02). Owned by
 * gamification-service; user-service keeps a projection updated from
 * {@code TrustScoreUpdated} events. {@code userId} is the platform-wide
 * authUserId. Pure domain: no framework dependencies.
 */
public final class TrustScore {

    public static final int INITIAL_SCORE = 100;
    public static final int MIN_SCORE = 0;
    public static final int MAX_SCORE = 100;

    private final UUID userId;
    private int score;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Long version;

    public TrustScore(UUID userId, int score, Instant createdAt, Instant updatedAt, Long version) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.score = score;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** A brand-new trust score: starts at 100 (matches user-service's default projection). */
    public static TrustScore createDefault(UUID userId, Instant now) {
        return new TrustScore(userId, INITIAL_SCORE, now, now, null);
    }

    /**
     * Applies a signed delta, clamped to the 0-100 range. Returns {@code true} when the
     * score actually changed (a delta absorbed entirely by the clamp is a no-op, so
     * callers can skip emitting an update event).
     */
    public boolean apply(int delta, Instant now) {
        int next = Math.max(MIN_SCORE, Math.min(MAX_SCORE, this.score + delta));
        if (next == this.score) {
            return false;
        }
        this.score = next;
        this.updatedAt = now;
        return true;
    }

    public UUID userId() {
        return userId;
    }

    public int score() {
        return score;
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
