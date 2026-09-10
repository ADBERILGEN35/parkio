package com.parkio.auth.domain;

import java.util.Locale;

/** Controls whether new account registration is open, invite-only, or closed. */
public enum RegistrationMode {
    CLOSED,
    INVITE,
    OPEN;

    public static RegistrationMode parse(String value) {
        if (value == null || value.isBlank()) {
            return CLOSED;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (RegistrationMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "Unknown parkio.registration.mode: '" + value + "'. Allowed: CLOSED, INVITE, OPEN.");
    }
}
