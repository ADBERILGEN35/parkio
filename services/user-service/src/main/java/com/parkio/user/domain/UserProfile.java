package com.parkio.user.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for a user's profile. Owns identity-adjacent profile data
 * (display name, optional phone/city) and account status. References the
 * auth-service account only by {@code authUserId} — no cross-service link
 * (ai-context/03). Pure domain: no framework dependencies.
 */
public final class UserProfile {

    public static final int DISPLAY_NAME_MIN = 2;
    public static final int DISPLAY_NAME_MAX = 50;

    private final UUID id;
    private final UUID authUserId;
    private final String email;
    private String displayName;
    private String phoneNumber;
    private String city;
    private UserStatus status;
    private final Instant createdAt;
    private final Long version;

    public UserProfile(UUID id,
                       UUID authUserId,
                       String email,
                       String displayName,
                       String phoneNumber,
                       String city,
                       UserStatus status,
                       Instant createdAt,
                       Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.authUserId = Objects.requireNonNull(authUserId, "authUserId");
        this.email = email;
        this.displayName = requireValidDisplayName(displayName);
        this.phoneNumber = normalize(phoneNumber);
        this.city = normalize(city);
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
    }

    /** Creates a new, active profile for the given auth account. */
    public static UserProfile create(UUID authUserId,
                                     String email,
                                     String displayName,
                                     String phoneNumber,
                                     String city,
                                     Instant now) {
        return new UserProfile(
                UUID.randomUUID(),
                authUserId,
                normalize(email),
                displayName,
                phoneNumber,
                city,
                UserStatus.ACTIVE,
                now,
                null);
    }

    /** Applies a partial profile update; {@code null} fields are left unchanged. */
    public void update(String displayName, String phoneNumber, String city) {
        if (displayName != null) {
            this.displayName = requireValidDisplayName(displayName);
        }
        if (phoneNumber != null) {
            this.phoneNumber = normalize(phoneNumber);
        }
        if (city != null) {
            this.city = normalize(city);
        }
    }

    /** Marks the account suspended (moderator-driven; auth credentials are unaffected). */
    public void suspend() {
        this.status = UserStatus.SUSPENDED;
    }

    /** Restores a suspended account to active (moderator-driven). */
    public void restore() {
        this.status = UserStatus.ACTIVE;
    }

    private static String requireValidDisplayName(String displayName) {
        Objects.requireNonNull(displayName, "displayName");
        String trimmed = displayName.trim();
        if (trimmed.length() < DISPLAY_NAME_MIN || trimmed.length() > DISPLAY_NAME_MAX) {
            throw new IllegalArgumentException(
                    "displayName must be between " + DISPLAY_NAME_MIN + " and " + DISPLAY_NAME_MAX + " characters");
        }
        return trimmed;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public UUID id() {
        return id;
    }

    public UUID authUserId() {
        return authUserId;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public String phoneNumber() {
        return phoneNumber;
    }

    public String city() {
        return city;
    }

    public UserStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Long version() {
        return version;
    }
}
