package com.parkio.gateway.infrastructure.client;

/**
 * Signals that the gateway could not determine a user's current session epoch from
 * auth-service (timeout, connection error, non-2xx response, unknown user). The edge
 * filter translates this into a fail-closed {@code 503} rather than letting a possibly
 * revoked access token through.
 */
public class SessionEpochUnavailableException extends RuntimeException {

    public SessionEpochUnavailableException(String message) {
        super(message);
    }

    public SessionEpochUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
