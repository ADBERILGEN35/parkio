package com.parkio.aivalidation.presentation.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Moderator/admin request to run a manual placeholder validation. {@code parkingSpotId}
 * is optional (a media object may not be attached to a spot yet).
 */
public record ManualValidationRequest(
        @NotNull UUID mediaId,
        UUID parkingSpotId) {
}
