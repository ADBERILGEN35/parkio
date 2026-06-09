package com.parkio.user.infrastructure.persistence.entity;

import com.parkio.user.domain.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code pending_user_status_events}. A persistence detail, not the domain. */
@Entity
@Table(name = "pending_user_status_events")
public class PendingUserStatusEventEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "auth_user_id", nullable = false, updatable = false)
    private UUID authUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_status", nullable = false, updatable = false)
    private UserStatus targetStatus;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "case_id", updatable = false)
    private UUID caseId;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected PendingUserStatusEventEntity() {
        // for JPA
    }

    public PendingUserStatusEventEntity(UUID id,
                                        UUID authUserId,
                                        UserStatus targetStatus,
                                        Instant occurredAt,
                                        UUID caseId,
                                        Instant recordedAt) {
        this.id = id;
        this.authUserId = authUserId;
        this.targetStatus = targetStatus;
        this.occurredAt = occurredAt;
        this.caseId = caseId;
        this.recordedAt = recordedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAuthUserId() {
        return authUserId;
    }

    public UserStatus getTargetStatus() {
        return targetStatus;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
