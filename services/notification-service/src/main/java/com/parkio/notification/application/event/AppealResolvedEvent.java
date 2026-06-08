package com.parkio.notification.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of moderation-service's {@code AppealResolved} payload (event-contracts.md).
 * Contracts are duplicated, never shared (ai-context/01). {@code userId} is the appellant.
 */
public record AppealResolvedEvent(
        UUID eventId,
        UUID appealId,
        UUID caseId,
        UUID userId,
        boolean accepted,
        Instant occurredAt) {

    public static final String TYPE = "AppealResolved";
}
