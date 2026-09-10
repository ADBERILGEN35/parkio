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
    SAVED_PLACE_LIMIT_EXCEEDED("Saved place limit exceeded."),
    FAVOURITES_DISABLED("Favourites is not enabled in this environment."),
    FAVOURITE_PARKING_NOT_FOUND("Favourite parking not found."),
    FAVOURITE_DESTINATION_NOT_FOUND("Favourite destination not found."),
    UNSUPPORTED_FAVOURITE_TARGET("Unsupported favourite target."),
    FAVOURITE_CONFLICT("Favourite conflict."),
    FAVOURITE_LIMIT_EXCEEDED("Favourite limit exceeded."),
    RECENTS_DISABLED("Recents is not enabled in this environment."),
    RECENT_DESTINATION_NOT_FOUND("Recent destination not found."),
    RECENT_PARKING_NOT_FOUND("Recent parking not found."),
    INVALID_RECENT_DESTINATION("Invalid recent destination."),
    INVALID_RECENT_PARKING_TARGET("Invalid recent parking target."),
    UNSUPPORTED_RECENT_PARKING_TARGET("Unsupported recent parking target.");

    private final String defaultMessage;

    UserErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
