package com.parkio.platform.http;

/**
 * Shared infrastructure header and MDC key names.
 *
 * <p>These constants describe transport plumbing only. They do not encode
 * service-specific authorization or domain semantics.
 */
public final class PlatformHeaders {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_EMAIL = "X-User-Email";
    public static final String USER_ROLES = "X-User-Roles";
    public static final String GATEWAY_AUTH = "X-Gateway-Auth";
    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String CORRELATION_ID_ATTRIBUTE = "parkio.correlationId";
    public static final String TOKEN_SESSION_EPOCH_ATTRIBUTE = "parkio.tokenSessionEpoch";

    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_EVENT_ID = "eventId";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_SERVICE = "service";

    public static final String KAFKA_EVENT_ID = "eventId";
    public static final String KAFKA_LEGACY_TRACE_ID = "traceId";
    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";
    public static final String BAGGAGE = "baggage";

    private PlatformHeaders() {
    }
}
