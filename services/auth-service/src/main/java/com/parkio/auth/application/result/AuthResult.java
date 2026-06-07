package com.parkio.auth.application.result;

import com.parkio.auth.domain.AuthUser;
import java.time.Instant;

/**
 * Outcome of a successful authentication (register / login / refresh). Carries
 * the raw refresh token — the only point in the system where it exists in raw
 * form, to be returned to the client and never persisted.
 */
public record AuthResult(
        AuthUser user,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt) {
}
