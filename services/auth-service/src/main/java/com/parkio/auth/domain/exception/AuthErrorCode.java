package com.parkio.auth.domain.exception;

/**
 * Stable, domain-level error codes. They are framework-free (no HTTP here);
 * the presentation layer maps them to HTTP status codes and consistent API
 * error bodies (ai-context/04).
 */
public enum AuthErrorCode {
    EMAIL_ALREADY_EXISTS("Email is already registered."),
    INVALID_CREDENTIALS("Invalid email or password."),
    INVALID_REFRESH_TOKEN("Refresh token is invalid or expired."),
    USER_NOT_ACTIVE("Account is not active."),
    USER_NOT_FOUND("User not found.");

    private final String defaultMessage;

    AuthErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
