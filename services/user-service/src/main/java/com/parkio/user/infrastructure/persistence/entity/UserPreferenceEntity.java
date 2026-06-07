package com.parkio.user.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/** JPA mapping for {@code user_preferences}. */
@Entity
@Table(name = "user_preferences")
public class UserPreferenceEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_profile_id", nullable = false, unique = true, updatable = false)
    private UUID userProfileId;

    @Column(name = "preferred_radius_meters", nullable = false)
    private int preferredRadiusMeters;

    @Column(name = "notifications_enabled", nullable = false)
    private boolean notificationsEnabled;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected UserPreferenceEntity() {
        // for JPA
    }

    public UserPreferenceEntity(UUID id,
                                UUID userProfileId,
                                int preferredRadiusMeters,
                                boolean notificationsEnabled,
                                Long version) {
        this.id = id;
        this.userProfileId = userProfileId;
        this.preferredRadiusMeters = preferredRadiusMeters;
        this.notificationsEnabled = notificationsEnabled;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserProfileId() {
        return userProfileId;
    }

    public int getPreferredRadiusMeters() {
        return preferredRadiusMeters;
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public Long getVersion() {
        return version;
    }
}
