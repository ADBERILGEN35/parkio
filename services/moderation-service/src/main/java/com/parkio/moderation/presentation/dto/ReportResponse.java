package com.parkio.moderation.presentation.dto;

import com.parkio.moderation.domain.ModerationReason;
import com.parkio.moderation.domain.ModerationTargetType;
import com.parkio.moderation.domain.UserReport;
import java.time.Instant;
import java.util.UUID;

/** Response view of a user report. */
public record ReportResponse(
        UUID id,
        UUID reporterUserId,
        ModerationTargetType targetType,
        UUID targetId,
        ModerationReason reason,
        String description,
        UUID caseId,
        Instant createdAt) {

    public static ReportResponse from(UserReport r) {
        return new ReportResponse(r.id(), r.reporterUserId(), r.targetType(), r.targetId(),
                r.reason(), r.description(), r.caseId(), r.createdAt());
    }
}
