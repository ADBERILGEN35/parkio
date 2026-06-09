package com.parkio.auth.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local DTO for moderation-service's {@code UserSuspended} event (consumed from
 * {@code parkio.moderation.action}). Contracts are duplicated per consumer — never a
 * shared module (ai-context/01, event-contracts.md). Intentionally a subset; unknown
 * producer fields are ignored by the configured ObjectMapper.
 */
public record UserSuspendedEvent(
        UUID eventId,
        UUID caseId,
        UUID userId,
        UUID moderatorId,
        Instant occurredAt) {

    public static final String TYPE = "UserSuspended";
}
