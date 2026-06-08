package com.parkio.moderation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only audit record of a moderator's decision on a case. Pure domain. */
public final class ModerationDecision {

    private final UUID id;
    private final UUID caseId;
    private final UUID moderatorId;
    private final ModerationAction action;
    private final String note;
    private final Instant createdAt;

    public ModerationDecision(UUID id, UUID caseId, UUID moderatorId, ModerationAction action,
                              String note, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.caseId = Objects.requireNonNull(caseId, "caseId");
        this.moderatorId = Objects.requireNonNull(moderatorId, "moderatorId");
        this.action = Objects.requireNonNull(action, "action");
        this.note = note;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ModerationDecision record(UUID caseId, UUID moderatorId, ModerationAction action,
                                            String note, Instant now) {
        return new ModerationDecision(UUID.randomUUID(), caseId, moderatorId, action, note, now);
    }

    public UUID id() {
        return id;
    }

    public UUID caseId() {
        return caseId;
    }

    public UUID moderatorId() {
        return moderatorId;
    }

    public ModerationAction action() {
        return action;
    }

    public String note() {
        return note;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
