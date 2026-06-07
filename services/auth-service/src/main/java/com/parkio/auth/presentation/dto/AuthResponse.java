package com.parkio.auth.presentation.dto;

import com.parkio.auth.application.result.AuthResult;
import java.time.Instant;

/**
 * Successful authentication response (register / login / refresh). The
 * refresh token is returned in its raw form here only.
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UserResponse user) {

    public static AuthResponse from(AuthResult result) {
        return new AuthResponse(
                result.accessToken(),
                "Bearer",
                result.accessTokenExpiresAt(),
                result.refreshToken(),
                result.refreshTokenExpiresAt(),
                UserResponse.from(result.user()));
    }
}
