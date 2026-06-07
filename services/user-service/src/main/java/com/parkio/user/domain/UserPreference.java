package com.parkio.user.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A user's preferences (1:1 with a {@link UserProfile}). Pure domain. The search
 * radius is clamped to a safe range so downstream geo queries stay bounded.
 */
public final class UserPreference {

    public static final int MIN_RADIUS_METERS = 100;
    public static final int MAX_RADIUS_METERS = 50_000;
    public static final int DEFAULT_RADIUS_METERS = 1_000;

    private final UUID id;
    private final UUID userProfileId;
    private int preferredRadiusMeters;
    private boolean notificationsEnabled;
    private final Long version;

    public UserPreference(UUID id,
                          UUID userProfileId,
                          int preferredRadiusMeters,
                          boolean notificationsEnabled,
                          Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userProfileId = Objects.requireNonNull(userProfileId, "userProfileId");
        this.preferredRadiusMeters = requireValidRadius(preferredRadiusMeters);
        this.notificationsEnabled = notificationsEnabled;
        this.version = version;
    }

    /** Creates the default preferences for a newly-created profile. */
    public static UserPreference createDefault(UUID userProfileId) {
        return new UserPreference(UUID.randomUUID(), userProfileId, DEFAULT_RADIUS_METERS, true, null);
    }

    /** Applies a partial update; {@code null} fields are left unchanged. */
    public void update(Integer preferredRadiusMeters, Boolean notificationsEnabled) {
        if (preferredRadiusMeters != null) {
            this.preferredRadiusMeters = requireValidRadius(preferredRadiusMeters);
        }
        if (notificationsEnabled != null) {
            this.notificationsEnabled = notificationsEnabled;
        }
    }

    private static int requireValidRadius(int radius) {
        if (radius < MIN_RADIUS_METERS || radius > MAX_RADIUS_METERS) {
            throw new IllegalArgumentException(
                    "preferredRadiusMeters must be between " + MIN_RADIUS_METERS + " and " + MAX_RADIUS_METERS);
        }
        return radius;
    }

    public UUID id() {
        return id;
    }

    public UUID userProfileId() {
        return userProfileId;
    }

    public int preferredRadiusMeters() {
        return preferredRadiusMeters;
    }

    public boolean notificationsEnabled() {
        return notificationsEnabled;
    }

    public Long version() {
        return version;
    }
}
