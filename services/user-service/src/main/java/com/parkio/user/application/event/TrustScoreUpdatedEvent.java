package com.parkio.user.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of gamification-service's {@code TrustScoreUpdated} payload
 * (event-contracts.md). Projected into {@code user_trust_profiles.trust_score}
 * (band derived) with an append-only {@code user_trust_score_history} entry keyed
 * by {@code reason} (the gamification trust rule key).
 * {@code aggregateVersion} is gamification's version of the aggregate behind the snapshot;
 * the projection keeps the highest one it has seen. It is {@code null} on events published
 * before U12.
 */
public record TrustScoreUpdatedEvent(
        UUID eventId,
        UUID userId,
        int previousScore,
        int newScore,
        String reason,
        UUID relatedEventId,
        Instant occurredAt,
        Long aggregateVersion) {

    public static final String TYPE = "TrustScoreUpdated";
}
