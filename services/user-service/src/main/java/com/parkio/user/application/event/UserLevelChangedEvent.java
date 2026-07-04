package com.parkio.user.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of gamification-service's {@code UserLevelChanged} payload
 * (event-contracts.md). Projected into {@code user_trust_profiles.current_level}.
 */
public record UserLevelChangedEvent(
        UUID eventId,
        UUID userId,
        int previousLevel,
        int newLevel,
        long totalPoints,
        Instant occurredAt) {

    public static final String TYPE = "UserLevelChanged";
}
