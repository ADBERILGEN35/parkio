package com.parkio.user.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of gamification-service's {@code PointsEarned} payload
 * (event-contracts.md). Contracts are duplicated, never shared (ai-context/01).
 * {@code totalPoints} is the absolute lifetime snapshot projected into
 * {@code user_trust_profiles.total_points}.
 * {@code aggregateVersion} is gamification's version of the aggregate behind the snapshot;
 * the projection keeps the highest one it has seen. It is {@code null} on events published
 * before U12.
 */
public record PointsEarnedEvent(
        UUID eventId,
        UUID userId,
        long points,
        String sourceType,
        long totalPoints,
        UUID relatedEventId,
        Instant occurredAt,
        Long aggregateVersion) {

    public static final String TYPE = "PointsEarned";
}
