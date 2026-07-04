package com.parkio.user.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of gamification-service's {@code TrustScoreUpdated} payload
 * (event-contracts.md). Projected into {@code user_trust_profiles.trust_score}
 * (band derived) with an append-only {@code user_trust_score_history} entry keyed
 * by {@code reason} (the gamification trust rule key).
 */
public record TrustScoreUpdatedEvent(
        UUID eventId,
        UUID userId,
        int previousScore,
        int newScore,
        String reason,
        UUID relatedEventId,
        Instant occurredAt) {

    public static final String TYPE = "TrustScoreUpdated";
}
