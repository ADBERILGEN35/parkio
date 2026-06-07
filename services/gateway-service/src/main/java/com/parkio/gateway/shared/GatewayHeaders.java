package com.parkio.gateway.shared;

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
    public static final String USER_ID = "X-User-Id";
    /** Verified user email (JWT {@code email}). */
    public static final String USER_EMAIL = "X-User-Email";
    /** Verified roles, comma-separated (JWT {@code roles}). */
    public static final String USER_ROLES = "X-User-Roles";

    /** Request correlation id, forwarded or generated at the edge. */
    public static final String CORRELATION_ID = "X-Correlation-Id";

    /** Exchange attribute key under which the resolved correlation id is stored. */
    public static final String CORRELATION_ID_ATTRIBUTE = "parkio.correlationId";

    private GatewayHeaders() {
    }
}
