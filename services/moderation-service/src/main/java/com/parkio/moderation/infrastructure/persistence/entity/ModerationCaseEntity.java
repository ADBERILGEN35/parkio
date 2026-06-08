package com.parkio.moderation.infrastructure.persistence.entity;

import com.parkio.moderation.domain.ModerationAction;
import com.parkio.moderation.domain.ModerationReason;
import com.parkio.moderation.domain.ModerationSeverity;
import com.parkio.moderation.domain.ModerationStatus;
import com.parkio.moderation.domain.ModerationTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code moderation_cases}. */
@Entity
@Table(name = "moderation_cases")
public class ModerationCaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false)
    private ModerationTargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false)
    private ModerationReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private ModerationSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ModerationStatus status;

    @Column(name = "assigned_moderator_id")
    private UUID assignedModeratorId;

    @Column(name = "report_count", nullable = false)
    private int reportCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_action")
    private ModerationAction resolutionAction;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ModerationCaseEntity() {
        // for JPA
    }

    public ModerationCaseEntity(UUID id, ModerationTargetType targetType, UUID targetId, ModerationReason reason,
                                ModerationSeverity severity, ModerationStatus status, UUID assignedModeratorId,
                                int reportCount, ModerationAction resolutionAction, String resolutionNote,
                                Instant openedAt, Instant updatedAt, Instant resolvedAt, Long version) {
        this.id = id;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reason = reason;
        this.severity = severity;
        this.status = status;
        this.assignedModeratorId = assignedModeratorId;
        this.reportCount = reportCount;
        this.resolutionAction = resolutionAction;
        this.resolutionNote = resolutionNote;
        this.openedAt = openedAt;
        this.updatedAt = updatedAt;
        this.resolvedAt = resolvedAt;
        this.version = version;
    }

    public UUID getId() {
        return id;
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

    public ModerationSeverity getSeverity() {
        return severity;
    }

    public ModerationStatus getStatus() {
        return status;
    }

    public UUID getAssignedModeratorId() {
        return assignedModeratorId;
    }

    public int getReportCount() {
        return reportCount;
    }

    public ModerationAction getResolutionAction() {
        return resolutionAction;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Long getVersion() {
        return version;
    }
}
