package com.parkio.parking.decision.assessment;

import java.util.Locale;
import java.util.Objects;

/**
 * Machine-readable reason token for assessments and decisions.
 *
 * <p>Core domain stores codes only — never localized user-facing messages.
 */
public record ReasonCode(String value) {

    private static final int MAX_LENGTH = 128;

    public ReasonCode {
        Objects.requireNonNull(value, "value");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("reason code must not be blank");
        }
        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("reason code must be at most " + MAX_LENGTH + " characters");
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (!(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '_') {
                throw new IllegalArgumentException(
                        "reason code must be UPPER_SNAKE_CASE alphanumeric: " + value);
            }
        }
        value = normalized;
    }

    public static ReasonCode of(String value) {
        return new ReasonCode(value);
    }
}