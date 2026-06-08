package com.parkio.moderation.domain;

import com.parkio.moderation.domain.exception.ModerationErrorCode;
import com.parkio.moderation.domain.exception.ModerationException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for a moderation case. Targets another service's entity by id only
 * ({@code targetId}); moderation-service never mutates that entity — it emits events.
 * Pure domain: no framework dependencies.
 */
public final class ModerationCase {

    private final UUID id;
    private final ModerationTargetType targetType;
    private final UUID targetId;
    /**
     * The owner of the targeted entity (the spot owner for PARKING_SPOT cases, the user
     * for USER cases), when known. Nullable: cases opened from a user report or an AI/media
     * signal don't know the owner. Lets the moderator-rejection event carry {@code ownerUserId}.
     */
    private final UUID ownerUserId;
    private final ModerationReason reason;
    private final ModerationSeverity severity;
    private ModerationStatus status;
    private UUID assignedModeratorId;
    private int reportCount;
    private ModerationAction resolutionAction;
    private String resolutionNote;
    private final Instant openedAt;
    private Instant updatedAt;
    private Instant resolvedAt;
    private final Long version;

    public ModerationCase(UUID id, ModerationTargetType targetType, UUID targetId, UUID ownerUserId,
                          ModerationReason reason, ModerationSeverity severity, ModerationStatus status,
                          UUID assignedModeratorId, int reportCount, ModerationAction resolutionAction,
                          String resolutionNote, Instant openedAt, Instant updatedAt, Instant resolvedAt,
                          Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.targetType = Objects.requireNonNull(targetType, "targetType");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.ownerUserId = ownerUserId;
        this.reason = Objects.requireNonNull(reason, "reason");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.status = Objects.requireNonNull(status, "status");
        this.assignedModeratorId = assignedModeratorId;
        this.reportCount = reportCount;
        this.resolutionAction = resolutionAction;
        this.resolutionNote = resolutionNote;
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.resolvedAt = resolvedAt;
        this.version = version;
    }

    /** Opens a new case with a first report counted. {@code ownerUserId} may be null. */
    public static ModerationCase open(ModerationTargetType targetType, UUID targetId, UUID ownerUserId,
                                      ModerationReason reason, ModerationSeverity severity, Instant now) {
        return new ModerationCase(UUID.randomUUID(), targetType, targetId, ownerUserId, reason, severity,
                ModerationStatus.OPEN, null, 1, null, null, now, now, null, null);
    }

    /** Records that another report fed into this still-open case. */
    public void registerAdditionalReport(Instant now) {
        this.reportCount++;
        this.updatedAt = now;
    }

    /** Assigns the case to a moderator (OPEN/IN_REVIEW only). */
    public void assignTo(UUID moderatorId, Instant now) {
        ensureNotTerminal();
        this.assignedModeratorId = Objects.requireNonNull(moderatorId, "moderatorId");
        this.status = ModerationStatus.IN_REVIEW;
        this.updatedAt = now;
    }

    /**
     * Resolves the case. {@link ModerationAction#APPROVE} dismisses it ({@code REJECTED}
     * status); any other action upholds it ({@code RESOLVED}).
     */
    public void resolve(ModerationAction action, String note, Instant now) {
        ensureNotTerminal();
        this.resolutionAction = Objects.requireNonNull(action, "action");
        this.resolutionNote = note;
        this.resolvedAt = now;
        this.updatedAt = now;
        this.status = action == ModerationAction.APPROVE ? ModerationStatus.REJECTED : ModerationStatus.RESOLVED;
    }

    private void ensureNotTerminal() {
        if (status.isTerminal()) {
            throw new ModerationException(ModerationErrorCode.INVALID_CASE_STATE,
                    "Case is already " + status + ".");
        }
    }

    public boolean isResolved() {
        return status == ModerationStatus.RESOLVED;
    }

    public boolean targetsUser(UUID userId) {
        return targetType == ModerationTargetType.USER && targetId.equals(userId);
    }

    public UUID id() {
        return id;
    }

    public ModerationTargetType targetType() {
        return targetType;
    }

    public UUID targetId() {
        return targetId;
    }

    /** The owner of the targeted entity, when known (nullable). */
    public UUID ownerUserId() {
        return ownerUserId;
    }

    public ModerationReason reason() {
        return reason;
    }

    public ModerationSeverity severity() {
        return severity;
    }

    public ModerationStatus status() {
        return status;
    }

    public UUID assignedModeratorId() {
        return assignedModeratorId;
    }

    public int reportCount() {
        return reportCount;
    }

    public ModerationAction resolutionAction() {
        return resolutionAction;
    }

    public String resolutionNote() {
        return resolutionNote;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }

    public Long version() {
        return version;
    }
}
