package com.parkio.user.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for the append-only {@code user_trust_score_history}. */
@Entity
@Table(name = "user_trust_score_history")
public class UserTrustScoreHistoryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_profile_id", nullable = false, updatable = false)
    private UUID userProfileId;

    @Column(name = "previous_score")
    private Integer previousScore;

    @Column(name = "new_score", nullable = false)
    private int newScore;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected UserTrustScoreHistoryEntity() {
        // for JPA
    }

    public UserTrustScoreHistoryEntity(UUID id,
                                       UUID userProfileId,
                                       Integer previousScore,
                                       int newScore,
                                       String reason,
                                       Instant occurredAt) {
        this.id = id;
        this.userProfileId = userProfileId;
        this.previousScore = previousScore;
        this.newScore = newScore;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserProfileId() {
        return userProfileId;
    }

    public Integer getPreviousScore() {
        return previousScore;
    }

    public int getNewScore() {
        return newScore;
    }

    public String getReason() {
        return reason;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
