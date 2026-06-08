package com.parkio.moderation.presentation.dto;

import com.parkio.moderation.domain.Appeal;
import com.parkio.moderation.domain.AppealStatus;
import java.time.Instant;
import java.util.UUID;

/** Response view of an appeal. */
public record AppealResponse(
        UUID id,
        UUID appealUserId,
        UUID caseId,
        String note,
        AppealStatus status,
        UUID resolverModeratorId,
        String resolutionNote,
        Instant createdAt,
        Instant resolvedAt) {

    public static AppealResponse from(Appeal a) {
        return new AppealResponse(a.id(), a.appealUserId(), a.caseId(), a.note(), a.status(),
                a.resolverModeratorId(), a.resolutionNote(), a.createdAt(), a.resolvedAt());
    }
}
