package com.parkio.moderation.application.event;

import java.time.Instant;
import java.util.UUID;

/** Local copy of media-service's {@code MediaRejected} payload (event-contracts.md). */
public record MediaRejectedEvent(
        UUID eventId,
        UUID mediaId,
        UUID ownerUserId,
        String validationType,
        String reason,
        String checksum,
        Instant occurredAt) {
}
