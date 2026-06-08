package com.parkio.moderation.domain;

import com.parkio.moderation.domain.exception.ModerationErrorCode;
import com.parkio.moderation.domain.exception.ModerationException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A user's appeal against a resolved case that affected them. Pure domain.
 */
public final class Appeal {

    private final UUID id;
    private final UUID appealUserId;
    private final UUID caseId;
    private final String note;
    private AppealStatus status;
    private UUID resolverModeratorId;
    private String resolutionNote;
    private final Instant createdAt;
    private Instant resolvedAt;
    private final Long version;

    public Appeal(UUID id, UUID appealUserId, UUID caseId, String note, AppealStatus status,
                  UUID resolverModeratorId, String resolutionNote, Instant createdAt,
                  Instant resolvedAt, Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.appealUserId = Objects.requireNonNull(appealUserId, "appealUserId");
        this.caseId = Objects.requireNonNull(caseId, "caseId");
        this.note = note;
        this.status = Objects.requireNonNull(status, "status");
        this.resolverModeratorId = resolverModeratorId;
        this.resolutionNote = resolutionNote;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.resolvedAt = resolvedAt;
        this.version = version;
    }

    public static Appeal create(UUID appealUserId, UUID caseId, String note, Instant now) {
        return new Appeal(UUID.randomUUID(), appealUserId, caseId, note, AppealStatus.OPEN, null, null, now, null, null);
    }

    public void resolve(boolean accepted, UUID moderatorId, String resolutionNote, Instant now) {
        if (status != AppealStatus.OPEN) {
            throw new ModerationException(ModerationErrorCode.INVALID_APPEAL_STATE,
                    "Appeal is already " + status + ".");
        }
        this.status = accepted ? AppealStatus.ACCEPTED : AppealStatus.REJECTED;
        this.resolverModeratorId = Objects.requireNonNull(moderatorId, "moderatorId");
        this.resolutionNote = resolutionNote;
        this.resolvedAt = now;
    }

    public boolean isAccepted() {
        return status == AppealStatus.ACCEPTED;
    }

    public UUID id() {
        return id;
    }

    public UUID appealUserId() {
        return appealUserId;
    }

    public UUID caseId() {
        return caseId;
    }

    public String note() {
        return note;
    }

    public AppealStatus status() {
        return status;
    }

    public UUID resolverModeratorId() {
        return resolverModeratorId;
    }

    public String resolutionNote() {
        return resolutionNote;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }

    public Long version() {
        return version;
    }
}
