package com.parkio.moderation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Append-only record of a penalty applied to a user as the outcome of a case. The
 * actual point/trust/status change happens in other services (via events); this is
 * moderation-service's own record. Pure domain.
 */
public final class UserViolation {

    private final UUID id;
    private final UUID userId;
    private final UUID caseId;
    private final ModerationReason reason;
    private final ModerationSeverity severity;
    private final ModerationAction action;
    private final Instant createdAt;

    public UserViolation(UUID id, UUID userId, UUID caseId, ModerationReason reason,
                         ModerationSeverity severity, ModerationAction action, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.caseId = Objects.requireNonNull(caseId, "caseId");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.action = Objects.requireNonNull(action, "action");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static UserViolation record(UUID userId, UUID caseId, ModerationReason reason,
                                       ModerationSeverity severity, ModerationAction action, Instant now) {
        return new UserViolation(UUID.randomUUID(), userId, caseId, reason, severity, action, now);
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID caseId() {
        return caseId;
    }

    public ModerationReason reason() {
        return reason;
    }

    public ModerationSeverity severity() {
        return severity;
    }

    public ModerationAction action() {
        return action;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
