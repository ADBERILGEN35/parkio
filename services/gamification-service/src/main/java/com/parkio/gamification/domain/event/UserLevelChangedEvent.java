package com.parkio.gamification.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a user's level changes (up or down). {@code aggregateVersion} is the
 * {@code user_level_progress} row version after this change, the same as on the points event of
 * the same change. It grows with every change to the user's points, so a projection keeps the
 * highest one it has seen instead of whichever event arrived last (U12).
 */
public record UserLevelChangedEvent(
        UUID eventId,
        UUID userId,
        int previousLevel,
        int newLevel,
        long totalPoints,
        Instant occurredAt,
        long aggregateVersion) implements GamificationEvent {

    public static final String TYPE = "UserLevelChanged";

    public static UserLevelChangedEvent of(UUID userId, int previousLevel, int newLevel,
                                           long totalPoints, Instant occurredAt, long aggregateVersion) {
        return new UserLevelChangedEvent(UUID.randomUUID(), userId, previousLevel, newLevel,
                totalPoints, occurredAt, aggregateVersion);
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
