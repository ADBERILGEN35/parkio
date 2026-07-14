package com.parkio.user.domain;

import java.util.Locale;
import java.util.Objects;

/** Supported UI locales stored on user preferences. */
public enum PreferredLocale {
    TR("tr"),
    EN("en");

    public static final PreferredLocale DEFAULT = TR;

    private final String code;

    PreferredLocale(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static PreferredLocale fromCode(String code) {
        Objects.requireNonNull(code, "code");
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (PreferredLocale locale : values()) {
            if (locale.code.equals(normalized)) {
                return locale;
            }
        }
        throw new IllegalArgumentException("Unsupported locale: " + code);
    }

    public static PreferredLocale parseOrDefault(String code) {
        if (code == null || code.isBlank()) {
            return DEFAULT;
        }
        try {
            return fromCode(code);
        } catch (IllegalArgumentException ex) {
            return DEFAULT;
        }
    }
}