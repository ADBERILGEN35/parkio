package com.parkio.gamification.domain.event;

import com.parkio.gamification.domain.PointSourceType;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a user loses points (penalty).
 * {@code aggregateVersion} is the {@code user_level_progress} row version after this change. It
 * grows with every change to the user's points, so a projection keeps the highest one it has
 * seen instead of whichever event arrived last (U12).
 */
public record PointsDeductedEvent(
        UUID eventId,
        UUID userId,
        long points,
        PointSourceType sourceType,
        long totalPoints,
        UUID relatedEventId,
        Instant occurredAt,
        long aggregateVersion) implements GamificationEvent {

    public static final String TYPE = "PointsDeducted";

    public static PointsDeductedEvent of(UUID userId, long points, PointSourceType sourceType,
                                         long totalPoints, UUID relatedEventId, Instant occurredAt,
                                         long aggregateVersion) {
        return new PointsDeductedEvent(UUID.randomUUID(), userId, points, sourceType, totalPoints,
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
