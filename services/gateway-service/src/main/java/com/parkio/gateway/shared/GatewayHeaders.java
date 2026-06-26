package com.parkio.gateway.shared;

import com.parkio.platform.http.PlatformHeaders;

/**
 * Header names exchanged at the edge.
 *
 * <p>The {@code X-User-*} headers carry the verified identity the gateway injects
 * for downstream services. They are <strong>internal trust</strong> headers: the
 * gateway strips any client-supplied copies and re-injects them only after a
 * successful JWT validation. Downstream services may trust them precisely because
 * they are reachable only on the internal network, behind this gateway.
 */
public final class GatewayHeaders {

    /** Verified user id (JWT {@code sub}). */
    public static final String USER_ID = PlatformHeaders.USER_ID;
    /** Verified user email (JWT {@code email}). */
    public static final String USER_EMAIL = PlatformHeaders.USER_EMAIL;
    /** Verified roles, comma-separated (JWT {@code roles}). */
    public static final String USER_ROLES = PlatformHeaders.USER_ROLES;

    /**
     * Shared-secret header proving a request originated from this gateway. The gateway
     * strips any client-supplied copy and injects the configured secret on every routed
     * request; downstream services reject requests that lack the correct value, so a
     * directly-reachable service cannot be called without going through the gateway.
     */
    public static final String GATEWAY_AUTH = PlatformHeaders.GATEWAY_AUTH;

    /** Request correlation id, forwarded or generated at the edge. */
    public static final String CORRELATION_ID = PlatformHeaders.CORRELATION_ID;

    /** Exchange attribute key under which the resolved correlation id is stored. */
    public static final String CORRELATION_ID_ATTRIBUTE = PlatformHeaders.CORRELATION_ID_ATTRIBUTE;

    /**
     * Exchange attribute key under which authentication stores the {@code session_epoch}
     * claim from the validated access token (a {@link Long}, or absent for legacy tokens).
     * The session-epoch filter reads it to compare against the user's current epoch. Kept
     * as an exchange attribute, not a downstream header — only the gateway needs it.
     */
    public static final String TOKEN_SESSION_EPOCH_ATTRIBUTE = PlatformHeaders.TOKEN_SESSION_EPOCH_ATTRIBUTE;

    private GatewayHeaders() {
    }
}
