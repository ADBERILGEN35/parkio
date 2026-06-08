package com.parkio.moderation.presentation.dto;

import com.parkio.moderation.domain.ModerationAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Moderator's request to resolve a case with a decisive action. */
public record ResolveCaseRequest(
        @NotNull ModerationAction action,
        @Size(max = 2000) String note) {
}
