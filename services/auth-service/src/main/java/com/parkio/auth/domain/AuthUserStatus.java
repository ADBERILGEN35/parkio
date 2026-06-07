package com.parkio.auth.domain;

/**
 * Lifecycle status of an authentication account.
 *
 * <p>Only {@link #ACTIVE} accounts may authenticate. {@code SUSPENDED} and
 * {@code BANNED} are driven by moderation outcomes (ai-context/02); they are
 * applied here via future events, not decided by auth-service.
 */
public enum AuthUserStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    SUSPENDED,
    BANNED;

    public boolean canAuthenticate() {
        return this == ACTIVE;
    }
}
