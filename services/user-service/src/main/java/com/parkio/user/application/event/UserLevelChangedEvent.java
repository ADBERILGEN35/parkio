package com.parkio.user.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of gamification-service's {@code UserLevelChanged} payload
 * (event-contracts.md). Projected into {@code user_trust_profiles.current_level}.
 * {@code aggregateVersion} is gamification's version of the aggregate behind the snapshot;
 * the projection keeps the highest one it has seen. It is {@code null} on events published
 * before U12.
 */
public record UserLevelChangedEvent(
        UUID eventId,
        UUID userId,
        int previousLevel,
        int newLevel,
        long totalPoints,
        Instant occurredAt,
        Long aggregateVersion) {

    public static final String TYPE = "UserLevelChanged";
}
