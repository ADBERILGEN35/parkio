package com.parkio.auth.presentation.dto;

import java.time.Instant;

/** Response for a newly created registration invite (plaintext token shown once). */
public record RegistrationInviteResponse(String token, Instant expiresAt) {
}
