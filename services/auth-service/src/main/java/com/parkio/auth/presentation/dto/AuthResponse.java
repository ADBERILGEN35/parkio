package com.parkio.auth.presentation.dto;

import com.parkio.auth.application.result.AuthResult;
import com.parkio.auth.application.result.RegisterResult;
import java.time.Instant;

/**
 * Successful authentication response (register / login / refresh). The raw
 * refresh token is transported only as an HttpOnly cookie by the controller.
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt,
        UserResponse user) {

    public static AuthResponse from(AuthResult result) {
        return new AuthResponse(
                result.accessToken(),
                "Bearer",
                result.accessTokenExpiresAt(),
                result.refreshTokenExpiresAt(),
                UserResponse.from(result.user()));
    }

    public static AuthResponse pendingVerification(RegisterResult result) {
        return new AuthResponse(
                null,
                "Bearer",
                null,
                null,
                UserResponse.from(result.user()));
    }
}
