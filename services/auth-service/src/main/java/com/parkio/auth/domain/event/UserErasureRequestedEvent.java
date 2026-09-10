package com.parkio.auth.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Durable command to erase one account. Payload is ids only — no email or profile fields. */
public record UserErasureRequestedEvent(
        UUID eventId,
        UUID erasureRequestId,
        UUID authUserId,
        Instant occurredAt) {

    public static final String TYPE = "UserErasureRequested";
    public static final String AGGREGATE_TYPE = "AccountErasure";

    public static UserErasureRequestedEvent of(UUID erasureRequestId, UUID authUserId, Instant occurredAt) {
        return new UserErasureRequestedEvent(UUID.randomUUID(), erasureRequestId, authUserId, occurredAt);
    }
}
