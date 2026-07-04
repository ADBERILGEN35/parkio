package com.parkio.gamification.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of moderation-service's {@code ModerationCaseResolved} payload
 * (event-contracts.md). Contracts are duplicated, never shared (ai-context/01).
 * Gamification only acts on USER-targeted cases whose action is
 * {@code REDUCE_TRUST} (trust penalty) or {@code DEDUCT_POINTS} (point penalty
 * selected by {@code reason}).
 */
public record ModerationCaseResolvedEvent(
        UUID eventId,
        UUID caseId,
        String targetType,
        UUID targetId,
        String action,
        String reason,
        UUID moderatorId,
        Instant occurredAt) {

    public static final String TYPE = "ModerationCaseResolved";

    public static final String TARGET_TYPE_USER = "USER";
    public static final String ACTION_REDUCE_TRUST = "REDUCE_TRUST";
    public static final String ACTION_DEDUCT_POINTS = "DEDUCT_POINTS";

    /** Whether this case targets a user (as opposed to a parking spot). */
    public boolean targetsUser() {
        return TARGET_TYPE_USER.equals(targetType);
    }
}
