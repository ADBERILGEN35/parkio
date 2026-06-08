package com.parkio.moderation.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Request to appeal a resolved case. The appellant is the authenticated user. */
public record CreateAppealRequest(
        @NotNull UUID caseId,
        @Size(max = 2000) String note) {
}
