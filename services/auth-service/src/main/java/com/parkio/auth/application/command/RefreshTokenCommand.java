package com.parkio.auth.application.command;

/**
 * Rotate a refresh token. {@code clientKey} is the gateway-resolved client of the request (the same key
 * the login throttle uses); a successful rotation keeps that client known for the account (CL-F15 v3).
 * Null or blank means the client is not identified.
 */
public record RefreshTokenCommand(String rawRefreshToken, String clientKey) {

    public RefreshTokenCommand(String rawRefreshToken) {
        this(rawRefreshToken, null);
    }
}
