package com.parkio.gamification.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a user's trust score changes. {@code reason} is the stable trust rule
 * key that caused the change, so projections (user-service history) can record it.
 * {@code aggregateVersion} is the {@code trust_scores} row version after this change, so a
 * projection keeps the highest one it has seen instead of the last to arrive (U12).
 */
public record TrustScoreUpdatedEvent(
        UUID eventId,
        UUID userId,
        int previousScore,
        int newScore,
        String reason,
        UUID relatedEventId,
        Instant occurredAt,
        long aggregateVersion) implements GamificationEvent {

    public static final String TYPE = "TrustScoreUpdated";

    public static TrustScoreUpdatedEvent of(UUID userId, int previousScore, int newScore,
                                            String reason, UUID relatedEventId, Instant occurredAt,
                                            long aggregateVersion) {
        return new TrustScoreUpdatedEvent(UUID.randomUUID(), userId, previousScore, newScore,
                reason, relatedEventId, occurredAt, aggregateVersion);
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
