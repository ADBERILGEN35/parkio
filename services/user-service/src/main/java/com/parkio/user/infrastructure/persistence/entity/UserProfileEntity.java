package com.parkio.user.infrastructure.persistence.entity;

import com.parkio.user.domain.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code user_profiles}. A persistence detail, not the domain. */
@Entity
@Table(name = "user_profiles")
public class UserProfileEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "auth_user_id", nullable = false, unique = true, updatable = false)
    private UUID authUserId;

    @Column(name = "email")
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "city")
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected UserProfileEntity() {
        // for JPA
    }

    public UserProfileEntity(UUID id,
                             UUID authUserId,
                             String email,
                             String displayName,
                             String phoneNumber,
                             String city,
                             UserStatus status,
                             Instant createdAt,
                             Long version) {
        this.id = id;
        this.authUserId = authUserId;
        this.email = email;
        this.displayName = displayName;
        this.phoneNumber = phoneNumber;
        this.city = city;
        this.status = status;
        this.createdAt = createdAt;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAuthUserId() {
        return authUserId;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getCity() {
        return city;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }
}
