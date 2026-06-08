package com.parkio.notification.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of moderation-service's {@code ModerationCaseResolved} payload
 * (event-contracts.md). Contracts are duplicated, never shared (ai-context/01). The
 * notified user is {@code targetId} only when {@code targetType == "USER"}; otherwise the
 * case targets a spot/media and there is no user to notify.
 */
public record ModerationCaseResolvedEvent(
        UUID eventId,
        UUID caseId,
        String targetType,
        UUID targetId,
        String action,
        UUID moderatorId,
        Instant occurredAt) {

    public static final String TYPE = "ModerationCaseResolved";
    public static final String TARGET_TYPE_USER = "USER";
}
