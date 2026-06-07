package com.parkio.user.domain;

/**
 * Lifecycle status of a user account, owned by user-service. New profiles start
 * {@link #ACTIVE}; {@code SUSPENDED}/{@code BANNED} are driven by moderation
 * outcomes (ai-context/02), applied here later via events.
 */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    BANNED
}
