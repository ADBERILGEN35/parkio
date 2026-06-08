package com.parkio.moderation.application.command;

import com.parkio.moderation.domain.ModerationReason;
import com.parkio.moderation.domain.ModerationTargetType;
import java.util.UUID;

/** A user's request to report a target. */
public record CreateReportCommand(
        UUID reporterUserId,
        ModerationTargetType targetType,
        UUID targetId,
        ModerationReason reason,
        String description) {
}
