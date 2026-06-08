package com.parkio.gamification.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Emitted when a user's contribution score is recomputed. */
public record ContributionScoreUpdatedEvent(
        UUID eventId,
        UUID userId,
        long contributionScore,
        Instant occurredAt) implements GamificationEvent {

    public static final String TYPE = "ContributionScoreUpdated";

    public static ContributionScoreUpdatedEvent of(UUID userId, long contributionScore, Instant occurredAt) {
        return new ContributionScoreUpdatedEvent(UUID.randomUUID(), userId, contributionScore, occurredAt);
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
