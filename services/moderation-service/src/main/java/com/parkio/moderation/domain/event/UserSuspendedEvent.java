package com.parkio.moderation.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Emitted when a moderator suspends a user. user-service reacts to set account status. */
public record UserSuspendedEvent(
        UUID eventId,
        UUID caseId,
        UUID userId,
        UUID moderatorId,
        Instant occurredAt) implements ModerationEvent {

    public static final String TYPE = "UserSuspended";
    public static final String AGGREGATE_TYPE = "User";

    public static UserSuspendedEvent of(UUID caseId, UUID userId, UUID moderatorId, Instant occurredAt) {
        return new UserSuspendedEvent(UUID.randomUUID(), caseId, userId, moderatorId, occurredAt);
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
