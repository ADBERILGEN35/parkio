package com.parkio.moderation.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Emitted when a suspended user is restored (e.g. an accepted appeal). */
public record UserRestoredEvent(
        UUID eventId,
        UUID caseId,
        UUID userId,
        UUID moderatorId,
        Instant occurredAt) implements ModerationEvent {

    public static final String TYPE = "UserRestored";
    public static final String AGGREGATE_TYPE = "User";

    public static UserRestoredEvent of(UUID caseId, UUID userId, UUID moderatorId, Instant occurredAt) {
        return new UserRestoredEvent(UUID.randomUUID(), caseId, userId, moderatorId, occurredAt);
    }

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    @Override
    public UUID aggregateId() {
        return userId;
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
