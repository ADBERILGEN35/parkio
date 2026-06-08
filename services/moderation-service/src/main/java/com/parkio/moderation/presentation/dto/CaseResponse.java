package com.parkio.moderation.presentation.dto;

import com.parkio.moderation.domain.ModerationAction;
import com.parkio.moderation.domain.ModerationCase;
import com.parkio.moderation.domain.ModerationReason;
import com.parkio.moderation.domain.ModerationSeverity;
import com.parkio.moderation.domain.ModerationStatus;
import com.parkio.moderation.domain.ModerationTargetType;
import java.time.Instant;
import java.util.UUID;

/** Response view of a moderation case (moderator/admin only). */
public record CaseResponse(
        UUID id,
        ModerationTargetType targetType,
        UUID targetId,
        ModerationReason reason,
        ModerationSeverity severity,
        ModerationStatus status,
        UUID assignedModeratorId,
        int reportCount,
        ModerationAction resolutionAction,
        String resolutionNote,
        Instant openedAt,
        Instant updatedAt,
        Instant resolvedAt) {

    public static CaseResponse from(ModerationCase c) {
        return new CaseResponse(c.id(), c.targetType(), c.targetId(), c.reason(), c.severity(), c.status(),
                c.assignedModeratorId(), c.reportCount(), c.resolutionAction(), c.resolutionNote(),
                c.openedAt(), c.updatedAt(), c.resolvedAt());
    }
}
