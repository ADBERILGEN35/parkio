package com.parkio.gateway.infrastructure.client;

/**
 * Raised when the gateway cannot determine a user's account status (user-service
 * unreachable, timed out, or returned an unexpected error). The gateway fails closed:
 * protected requests are rejected with {@code 503 USER_STATUS_UNAVAILABLE} rather than
 * being let through on an unknown status.
 */
public class UserStatusUnavailableException extends RuntimeException {

    public UserStatusUnavailableException(String message) {
        super(message);
    }

    public UserStatusUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
