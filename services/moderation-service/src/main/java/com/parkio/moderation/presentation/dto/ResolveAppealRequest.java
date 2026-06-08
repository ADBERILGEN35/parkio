package com.parkio.moderation.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Moderator's decision on an appeal. */
public record ResolveAppealRequest(
        @NotNull Boolean accepted,
        @Size(max = 2000) String note) {
}
