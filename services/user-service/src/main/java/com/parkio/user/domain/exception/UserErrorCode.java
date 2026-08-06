package com.parkio.user.domain.exception;

/**
 * Stable, domain-level error codes. Framework-free (no HTTP here); the
 * presentation layer maps them to HTTP status codes and consistent API error
 * bodies (ai-context/04).
 */
public enum UserErrorCode {
    PROFILE_NOT_FOUND("User profile not found."),
    PROFILE_ALREADY_EXISTS("A profile already exists for this user."),
    MISSING_USER_ID("Authenticated user id (X-User-Id) is required."),
    SMART_RETURN_DISABLED("Smart Return is not enabled in this environment."),
    SAVED_PLACES_DISABLED("Saved Places is not enabled in this environment."),
    SAVED_PLACE_NOT_FOUND("Saved place not found."),
    SAVED_PLACE_CONFLICT("Saved place conflict."),
    SAVED_PLACE_LIMIT_EXCEEDED("Saved place limit exceeded.");

    private final String defaultMessage;

    UserErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
