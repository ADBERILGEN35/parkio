package com.parkio.moderation.domain.event;

import com.parkio.moderation.domain.Appeal;
import java.time.Instant;
import java.util.UUID;

/** Emitted when a user files an appeal. */
public record AppealCreatedEvent(
        UUID eventId,
        UUID appealId,
        UUID caseId,
        UUID userId,
        Instant occurredAt) implements ModerationEvent {

    public static final String TYPE = "AppealCreated";
    public static final String AGGREGATE_TYPE = "Appeal";

    public static AppealCreatedEvent of(Appeal appeal, Instant occurredAt) {
        return new AppealCreatedEvent(UUID.randomUUID(), appeal.id(), appeal.caseId(),
                appeal.appealUserId(), occurredAt);
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
