package com.parkio.gamification.domain.event;

import com.parkio.gamification.domain.PointSourceType;
import java.time.Instant;
import java.util.UUID;

/** Emitted when a user earns points. */
public record PointsEarnedEvent(
        UUID eventId,
        UUID userId,
        long points,
        PointSourceType sourceType,
        long totalPoints,
        UUID relatedEventId,
        Instant occurredAt) implements GamificationEvent {

    public static final String TYPE = "PointsEarned";

    public static PointsEarnedEvent of(UUID userId, long points, PointSourceType sourceType,
                                       long totalPoints, UUID relatedEventId, Instant occurredAt) {
        return new PointsEarnedEvent(UUID.randomUUID(), userId, points, sourceType, totalPoints,
                relatedEventId, occurredAt);
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
