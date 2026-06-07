package com.parkio.user.presentation.dto;

import com.parkio.user.domain.UserPreference;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Partial preferences update (PATCH). {@code null} fields are left unchanged.
 * The radius, when supplied, is bounded to a safe range.
 */
public record PreferencesRequest(
        @Min(UserPreference.MIN_RADIUS_METERS)
        @Max(UserPreference.MAX_RADIUS_METERS)
        Integer preferredRadiusMeters,
        Boolean notificationsEnabled) {
}
