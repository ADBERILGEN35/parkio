package com.parkio.gateway.infrastructure.client;

/**
 * Outcome of a user-service status lookup. {@code found == false} means user-service
 * answered {@code 404} (no profile yet / unknown account) — distinct from the lookup
 * being <em>unavailable</em>, which surfaces as a {@link UserStatusUnavailableException}.
 */
public record UserStatusLookup(boolean found, String status) {

    public static UserStatusLookup found(String status) {
        return new UserStatusLookup(true, status);
    }

    public static UserStatusLookup notFound() {
        return new UserStatusLookup(false, null);
    }
}
