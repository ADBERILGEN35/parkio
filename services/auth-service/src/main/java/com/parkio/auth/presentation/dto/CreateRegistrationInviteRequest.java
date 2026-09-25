package com.parkio.auth.presentation.dto;

import jakarta.validation.constraints.Size;

/** Optional metadata when creating a registration invite. */
public record CreateRegistrationInviteRequest(@Size(max = 200) String createdBy) {
}
