package com.parkio.auth.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event emitted when a new account is registered. Persisted to the
 * outbox in the same transaction as the registration (ai-context/06). Carries
 * only IDs and minimal data — no other service's model.
 */
public record UserRegisteredEvent(
        UUID eventId,
        UUID userId,
        String email,
        Instant occurredAt) {

    public static final String TYPE = "UserRegistered";
    public static final String AGGREGATE_TYPE = "AuthUser";

    public static UserRegisteredEvent of(UUID userId, String email, Instant occurredAt) {
        return new UserRegisteredEvent(UUID.randomUUID(), userId, email, occurredAt);
    }
}
