package com.parkio.auth.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.parkio.auth.application.result.AuthResult;
import com.parkio.auth.application.result.RegisterResult;
import java.time.Instant;

/**
 * Successful authentication response (register / login / refresh).
 *
 * <p>By default (web clients) the raw refresh token is transported <em>only</em>
 * as an HttpOnly cookie by the controller and {@link #refreshToken} stays
 * {@code null}. The {@code @JsonInclude(NON_NULL)} on that field means the
 * property is omitted entirely from the serialized body, so a browser response is
 * byte-identical to before this field existed and the refresh token is never
 * exposed to JavaScript.
 *
 * <p>Native mobile clients (identified by the {@code X-Parkio-Client: mobile}
 * request header) cannot use a browser cookie jar, so the controller builds the
 * response with {@link #fromMobile(AuthResult)} which populates {@link #refreshToken}
 * in the body for the app to persist in its secure keystore. See
 * {@code docs/mobile-architecture.md} → Token flow.
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String refreshToken,
        UserResponse user) {

    /** Web response: refresh token carried by HttpOnly cookie only (never in body). */
    public static AuthResponse from(AuthResult result) {
        return new AuthResponse(
                result.accessToken(),
                "Bearer",
                result.accessTokenExpiresAt(),
                result.refreshTokenExpiresAt(),
                null,
                UserResponse.from(result.user()));
    }

    /** Mobile response: refresh token returned in the body for SecureStore persistence. */
    public static AuthResponse fromMobile(AuthResult result) {
        return new AuthResponse(
                result.accessToken(),
                "Bearer",
                result.accessTokenExpiresAt(),
                result.refreshTokenExpiresAt(),
                result.refreshToken(),
                UserResponse.from(result.user()));
    }

    public static AuthResponse pendingVerification(RegisterResult result) {
        return new AuthResponse(
                null,
                "Bearer",
                null,
                null,
                null,
                UserResponse.from(result.user()));
    }
}
