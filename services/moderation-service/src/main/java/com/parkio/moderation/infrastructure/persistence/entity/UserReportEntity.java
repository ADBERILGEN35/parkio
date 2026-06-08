package com.parkio.moderation.infrastructure.persistence.entity;

import com.parkio.moderation.domain.ModerationReason;
import com.parkio.moderation.domain.ModerationTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code user_reports}. */
@Entity
@Table(name = "user_reports")
public class UserReportEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "reporter_user_id", nullable = false, updatable = false)
    private UUID reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false)
    private ModerationTargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false)
    private ModerationReason reason;

    @Column(name = "description", updatable = false)
    private String description;

    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserReportEntity() {
        // for JPA
    }

    public UserReportEntity(UUID id, UUID reporterUserId, ModerationTargetType targetType, UUID targetId,
                            ModerationReason reason, String description, UUID caseId, Instant createdAt) {
        this.id = id;
        this.reporterUserId = reporterUserId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reason = reason;
        this.description = description;
        this.caseId = caseId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReporterUserId() {
        return reporterUserId;
    }

    public ModerationTargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public ModerationReason getReason() {
        return reason;
    }

    public String getDescription() {
        return description;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
