package com.parkio.gamification.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Emitted when a user's level changes (up or down). */
public record UserLevelChangedEvent(
        UUID eventId,
        UUID userId,
        int previousLevel,
        int newLevel,
        long totalPoints,
        Instant occurredAt) implements GamificationEvent {

    public static final String TYPE = "UserLevelChanged";

    public static UserLevelChangedEvent of(UUID userId, int previousLevel, int newLevel,
                                           long totalPoints, Instant occurredAt) {
        return new UserLevelChangedEvent(UUID.randomUUID(), userId, previousLevel, newLevel,
                totalPoints, occurredAt);
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
