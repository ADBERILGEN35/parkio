package com.parkio.user.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of gamification-service's {@code PointsDeducted} payload
 * (event-contracts.md). {@code totalPoints} is the absolute lifetime snapshot
 * projected into {@code user_trust_profiles.total_points}.
 */
public record PointsDeductedEvent(
        UUID eventId,
        UUID userId,
        long points,
        String sourceType,
        long totalPoints,
        UUID relatedEventId,
        Instant occurredAt) {

    public static final String TYPE = "PointsDeducted";
}
