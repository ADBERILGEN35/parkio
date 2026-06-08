package com.parkio.moderation.presentation.dto;

import com.parkio.moderation.domain.ModerationReason;
import com.parkio.moderation.domain.ModerationTargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Request to file a report against a target. The reporter is the authenticated user. */
public record CreateReportRequest(
        @NotNull ModerationTargetType targetType,
        @NotNull UUID targetId,
        @NotNull ModerationReason reason,
        @Size(max = 2000) String description) {
}
