package com.parkio.notification.application.event;

import java.time.Instant;
import java.util.UUID;

public record TrustScoreUpdatedEvent(
        UUID eventId,
        UUID userId,
        int previousScore,
        int newScore,
        String reason,
        UUID relatedEventId,
        Instant occurredAt) {
}