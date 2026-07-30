package com.parkio.parking.externalsource;

/**
 * Bounded municipal-source failure taxonomy for sync-run persistence, metrics,
 * health details, and structured logs. Wire values are lowercase snake_case.
 *
 * <p>Historical rows may still contain legacy values {@code contract}, {@code timeout},
 * and {@code http}. New classifications never emit those aliases.
 */
public enum MunicipalSourceFailureCategory {
    CONNECT_TIMEOUT("connect_timeout"),
    READ_TIMEOUT("read_timeout"),
    DNS_RESOLUTION("dns_resolution"),
    CONNECTION_REFUSED("connection_refused"),
    TLS_FAILURE("tls_failure"),
    UPSTREAM_4XX("upstream_4xx"),
    UPSTREAM_5XX("upstream_5xx"),
    RATE_LIMITED("rate_limited"),
    AUTHENTICATION("authentication"),
    RESPONSE_TOO_LARGE("response_too_large"),
    SCHEMA_CONTRACT("schema_contract"),
    DESERIALIZATION("deserialization"),
    INVALID_SOURCE_DATA("invalid_source_data"),
    DATABASE("database"),
    CONCURRENT_RUN("concurrent_run"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown");

    private final String wireValue;

    MunicipalSourceFailureCategory(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public boolean isSchemaMismatch() {
        return this == SCHEMA_CONTRACT;
    }

    /** True for legacy or current schema-contract wire values. */
    public static boolean isSchemaMismatchWire(String wire) {
        return SCHEMA_CONTRACT.wireValue.equals(wire) || "contract".equals(wire);
    }
}
