package com.parkio.moderation.domain.event;

import com.parkio.moderation.domain.Appeal;
import java.time.Instant;
import java.util.UUID;

/** Emitted when a moderator resolves an appeal (accepted or rejected). */
public record AppealResolvedEvent(
        UUID eventId,
        UUID appealId,
        UUID caseId,
        UUID userId,
        boolean accepted,
        Instant occurredAt) implements ModerationEvent {

    public static final String TYPE = "AppealResolved";
    public static final String AGGREGATE_TYPE = "Appeal";

    public static AppealResolvedEvent of(Appeal appeal, Instant occurredAt) {
        return new AppealResolvedEvent(UUID.randomUUID(), appeal.id(), appeal.caseId(),
                appeal.appealUserId(), appeal.isAccepted(), occurredAt);
    }

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    @Override
    public UUID aggregateId() {
        return appealId;
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
