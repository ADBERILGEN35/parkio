package com.parkio.auth.domain;

/**
 * Supported locales for transactional auth emails.
 * Only allowlisted values are accepted; unknown input soft-falls back to {@link #TR}.
 */
public enum EmailLocale {
    TR("tr"),
    EN("en");

    private final String code;

    EmailLocale(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * Soft-parses an optional locale string. Null, blank, or unsupported values map to Turkish.
     * Never use the raw input for filesystem paths.
     */
    public static EmailLocale fromNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return TR;
        }
        String normalized = raw.trim().toLowerCase();
        if ("en".equals(normalized)) {
            return EN;
        }
        if ("tr".equals(normalized)) {
            return TR;
        }
        return TR;
    }
}