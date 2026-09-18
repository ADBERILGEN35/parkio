package com.parkio.parking.externalsource;

/**
 * Bounded municipal source operating mode for SLA evaluation (DATA-WP-16).
 *
 * <p>{@link #SCHEDULED} sources are expected to sync on a cadence; seconds-since-success
 * thresholds apply. {@link #OPERATOR_IMPORTED} sources are updated by operator action;
 * age alone is observational and must not drive CRITICAL/DEGRADED when mode-aware SLA
 * is enabled.
 */
public enum MunicipalSourceOperatingMode {
    SCHEDULED,
    OPERATOR_IMPORTED;

    public static MunicipalSourceOperatingMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("operating mode must not be blank");
        }
        try {
            return MunicipalSourceOperatingMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "unsupported municipal source operating mode: " + raw.trim());
        }
    }
}
