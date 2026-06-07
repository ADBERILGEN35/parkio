package com.parkio.user.presentation.dto;

import jakarta.validation.constraints.Size;

/**
 * Partial profile update (PATCH). All fields are optional; a {@code null} field
 * means "leave unchanged". When supplied, {@code displayName} must be 2–50 chars.
 */
public record UpdateProfileRequest(
        @Size(min = 2, max = 50, message = "displayName must be between 2 and 50 characters") String displayName,
        @Size(max = 32) String phoneNumber,
        @Size(max = 100) String city) {
}
