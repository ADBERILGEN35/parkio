package com.parkio.gateway.application.waitlist;

import java.util.regex.Pattern;

/**
 * Shared full-name normalization and validation for waitlist signup.
 *
 * <ul>
 *   <li>Trim leading/trailing whitespace; reject blank / whitespace-only.</li>
 *   <li>Length after trim: 1–{@link #MAX_LENGTH} characters.</li>
 *   <li>Unicode letters and marks, spaces, apostrophe, right-single-quote, hyphen, period.</li>
 *   <li>Does not require two words or an ASCII-only input.</li>
 * </ul>
 */
public final class WaitlistFullName {

    public static final int MAX_LENGTH = 100;

    /**
     * Letters (any script), combining marks, spaces, ASCII/typographic apostrophe, hyphen, period.
     */
    private static final Pattern ALLOWED = Pattern.compile(
            "^[\\p{L}\\p{M}][\\p{L}\\p{M} .'\\-\u2019]*$",
            Pattern.UNICODE_CHARACTER_CLASS);

    private WaitlistFullName() {
    }

    /** @return trimmed name, or {@code null} when input is null/blank after trim */
    public static String normalizeOptional(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static boolean isValid(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        if (normalized.length() > MAX_LENGTH) {
            return false;
        }
        return ALLOWED.matcher(normalized).matches();
    }

    /**
     * Normalize and validate. Returns null when absent is allowed; throws when invalid
     * or when required and missing.
     *
     * <p>When {@code required} is false, missing/blank still yields null, but a
     * <em>supplied</em> invalid name is always rejected.
     */
    public static String requireOrOptional(String raw, boolean required) {
        String normalized = normalizeOptional(raw);
        if (normalized == null) {
            if (required) {
                throw new WaitlistFullNameException("WAITLIST_FULL_NAME_REQUIRED");
            }
            return null;
        }
        if (!isValid(normalized)) {
            throw new WaitlistFullNameException("WAITLIST_FULL_NAME_INVALID");
        }
        return normalized;
    }
}