package com.parkio.gamification.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a user's trust score changes. {@code reason} is the stable trust rule
 * key that caused the change, so projections (user-service history) can record it.
 */
public record TrustScoreUpdatedEvent(
        UUID eventId,
        UUID userId,
        int previousScore,
        int newScore,
        String reason,
        UUID relatedEventId,
        Instant occurredAt) implements GamificationEvent {

    public static final String TYPE = "TrustScoreUpdated";

    public static TrustScoreUpdatedEvent of(UUID userId, int previousScore, int newScore,
                                            String reason, UUID relatedEventId, Instant occurredAt) {
        return new TrustScoreUpdatedEvent(UUID.randomUUID(), userId, previousScore, newScore,
                reason, relatedEventId, occurredAt);
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
