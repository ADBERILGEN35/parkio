package com.parkio.notification.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * Recipient UI locale for rendering notification title/body. Product default is
 * Turkish ({@link #DEFAULT}); unknown or blank codes fall back to that default.
 */
public enum NotificationLocale {
    TR("tr"),
    EN("en");

    /** Product default when the recipient locale is unknown. */
    public static final NotificationLocale DEFAULT = TR;

    private final String code;

    NotificationLocale(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static NotificationLocale fromCode(String code) {
        Objects.requireNonNull(code, "code");
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (NotificationLocale locale : values()) {
            if (locale.code.equals(normalized)) {
                return locale;
            }
        }
        throw new IllegalArgumentException("Unsupported locale: " + code);
    }

    public static NotificationLocale parseOrDefault(String code) {
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
