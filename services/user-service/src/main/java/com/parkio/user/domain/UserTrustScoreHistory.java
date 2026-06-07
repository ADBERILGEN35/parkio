package com.parkio.user.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An append-only record of a trust-score change for a user (audit trail of the
 * projection). {@code previousScore} is {@code null} for the initial entry.
 */
public final class UserTrustScoreHistory {

    public static final String REASON_INITIAL = "INITIAL";

    private final UUID id;
    private final UUID userProfileId;
    private final Integer previousScore;
    private final int newScore;
    private final String reason;
    private final Instant occurredAt;

    public UserTrustScoreHistory(UUID id,
                                 UUID userProfileId,
                                 Integer previousScore,
                                 int newScore,
                                 String reason,
                                 Instant occurredAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userProfileId = Objects.requireNonNull(userProfileId, "userProfileId");
        this.previousScore = previousScore;
        this.newScore = newScore;
        this.reason = Objects.requireNonNull(reason, "reason");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** Records a trust-score change. */
    public static UserTrustScoreHistory record(UUID userProfileId,
                                               Integer previousScore,
                                               int newScore,
                                               String reason,
                                               Instant occurredAt) {
        return new UserTrustScoreHistory(UUID.randomUUID(), userProfileId, previousScore, newScore, reason, occurredAt);
    }

    public UUID id() {
        return id;
    }

    public UUID userProfileId() {
        return userProfileId;
    }

    public Integer previousScore() {
        return previousScore;
    }

    public int newScore() {
        return newScore;
    }

    public String reason() {
        return reason;
    }

    public Instant occurredAt() {
        return occurredAt;
    }
}
