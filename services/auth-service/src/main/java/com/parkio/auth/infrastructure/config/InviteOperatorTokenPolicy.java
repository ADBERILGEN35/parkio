package com.parkio.auth.infrastructure.config;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The internal invite-creation operator token (#287 review I3).
 *
 * <p>With invite creation enabled, this token is the only credential of the internal invite
 * endpoint, so it must be a real secret: not empty, not an example-env placeholder, and at least
 * {@value #MIN_LENGTH} characters. The placeholder rule follows U17 (CL-F05R): the exact value the
 * example env files ship, and any value starting with {@code REPLACE_ME_}, compared
 * case-insensitively. A reason never contains the value.
 */
public final class InviteOperatorTokenPolicy {

    /** The minimum length; the example placeholder itself announces it ("min_32_chars"). */
    public static final int MIN_LENGTH = 32;

    /** Exact example-env values, compared case-insensitively. */
    static final Set<String> EXAMPLE_ENV_PLACEHOLDERS = Set.of("replace_me_invite_operator_token_min_32_chars");

    /** Any value with this prefix is a placeholder, whatever follows it. */
    static final String PLACEHOLDER_PREFIX = "replace_me_";

    private InviteOperatorTokenPolicy() {
    }

    /**
     * Why the token cannot serve as the operator token: {@code EMPTY}, {@code PLACEHOLDER} or
     * {@code TOO_SHORT}; empty when it can.
     */
    public static Optional<String> refusal(String token) {
        String value = token == null ? "" : token.strip();
        if (value.isEmpty()) {
            return Optional.of("EMPTY");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (EXAMPLE_ENV_PLACEHOLDERS.contains(normalized) || normalized.startsWith(PLACEHOLDER_PREFIX)) {
            return Optional.of("PLACEHOLDER");
        }
        if (value.length() < MIN_LENGTH) {
            return Optional.of("TOO_SHORT");
        }
        return Optional.empty();
    }
}
