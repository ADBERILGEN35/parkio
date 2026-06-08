package com.parkio.moderation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A report filed by a user against a target. {@code caseId} links to the case this
 * report opened or fed into (null until/unless a case exists). Pure domain.
 */
public final class UserReport {

    private final UUID id;
    private final UUID reporterUserId;
    private final ModerationTargetType targetType;
    private final UUID targetId;
    private final ModerationReason reason;
    private final String description;
    private UUID caseId;
    private final Instant createdAt;

    public UserReport(UUID id, UUID reporterUserId, ModerationTargetType targetType, UUID targetId,
                      ModerationReason reason, String description, UUID caseId, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.reporterUserId = Objects.requireNonNull(reporterUserId, "reporterUserId");
        this.targetType = Objects.requireNonNull(targetType, "targetType");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.description = description;
        this.caseId = caseId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static UserReport create(UUID reporterUserId, ModerationTargetType targetType, UUID targetId,
                                    ModerationReason reason, String description, Instant now) {
        return new UserReport(UUID.randomUUID(), reporterUserId, targetType, targetId, reason, description, null, now);
    }

    public void linkCase(UUID caseId) {
        this.caseId = caseId;
    }

    public boolean isSerious() {
        return reason.isSerious();
    }

    public UUID id() {
        return id;
    }

    public UUID reporterUserId() {
        return reporterUserId;
    }

    public ModerationTargetType targetType() {
        return targetType;
    }

    public UUID targetId() {
        return targetId;
    }

    public ModerationReason reason() {
        return reason;
    }

    public String description() {
        return description;
    }

    public UUID caseId() {
        return caseId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
