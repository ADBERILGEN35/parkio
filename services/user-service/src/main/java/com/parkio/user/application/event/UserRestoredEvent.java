package com.parkio.user.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of moderation-service's {@code UserRestored} payload (event-contracts.md).
 * Contracts are duplicated, never shared (ai-context/01). {@code userId} is the
 * platform-wide authUserId of the restored account.
 */
public record UserRestoredEvent(
        UUID eventId,
        UUID caseId,
        UUID userId,
        UUID moderatorId,
        Instant occurredAt) {

    public static final String TYPE = "UserRestored";
}
