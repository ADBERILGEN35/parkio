package com.parkio.gamification.domain.event;

import com.parkio.gamification.domain.PointSourceType;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a user earns points.
 * {@code aggregateVersion} is the {@code user_level_progress} row version after this change. It
 * grows with every change to the user's points, so a projection keeps the highest one it has
 * seen instead of whichever event arrived last (U12).
 */
public record PointsEarnedEvent(
        UUID eventId,
        UUID userId,
        long points,
        PointSourceType sourceType,
        long totalPoints,
        UUID relatedEventId,
        Instant occurredAt,
        long aggregateVersion) implements GamificationEvent {

    public static final String TYPE = "PointsEarned";

    public static PointsEarnedEvent of(UUID userId, long points, PointSourceType sourceType,
                                       long totalPoints, UUID relatedEventId, Instant occurredAt,
                                       long aggregateVersion) {
        return new PointsEarnedEvent(UUID.randomUUID(), userId, points, sourceType, totalPoints,
                relatedEventId, occurredAt, aggregateVersion);
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
