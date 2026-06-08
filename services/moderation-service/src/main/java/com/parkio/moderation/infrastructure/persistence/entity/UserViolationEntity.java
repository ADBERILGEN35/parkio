package com.parkio.moderation.infrastructure.persistence.entity;

import com.parkio.moderation.domain.ModerationAction;
import com.parkio.moderation.domain.ModerationReason;
import com.parkio.moderation.domain.ModerationSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code user_violations} (append-only). */
@Entity
@Table(name = "user_violations")
public class UserViolationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false)
    private ModerationReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, updatable = false)
    private ModerationSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false)
    private ModerationAction action;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserViolationEntity() {
        // for JPA
    }

    public UserViolationEntity(UUID id, UUID userId, UUID caseId, ModerationReason reason,
                               ModerationSeverity severity, ModerationAction action, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.caseId = caseId;
        this.reason = reason;
        this.severity = severity;
        this.action = action;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public ModerationReason getReason() {
        return reason;
    }

    public ModerationSeverity getSeverity() {
        return severity;
    }

    public ModerationAction getAction() {
        return action;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
