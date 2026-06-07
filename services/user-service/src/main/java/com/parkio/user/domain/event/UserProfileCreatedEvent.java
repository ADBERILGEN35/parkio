package com.parkio.user.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event emitted when a user profile is created. Persisted to the outbox
 * in the same transaction as the profile insert (ai-context/06). Carries only
 * IDs — no other service's model.
 */
public record UserProfileCreatedEvent(
        UUID eventId,
        UUID userProfileId,
        UUID authUserId,
        Instant occurredAt) {

    public static final String TYPE = "UserProfileCreated";
    public static final String AGGREGATE_TYPE = "UserProfile";

    public static UserProfileCreatedEvent of(UUID userProfileId, UUID authUserId, Instant occurredAt) {
        return new UserProfileCreatedEvent(UUID.randomUUID(), userProfileId, authUserId, occurredAt);
    }
}
