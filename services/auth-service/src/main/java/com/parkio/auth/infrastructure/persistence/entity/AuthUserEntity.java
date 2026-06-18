package com.parkio.auth.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import com.parkio.auth.domain.AuthUserStatus;

/**
 * JPA mapping for the {@code auth_users} table. A persistence detail: the
 * domain {@link com.parkio.auth.domain.AuthUser} never depends on it.
 */
@Entity
@Table(name = "auth_users")
public class AuthUserEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AuthUserStatus status;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "email_verification_token_hash", unique = true)
    private String emailVerificationTokenHash;

    @Column(name = "email_verification_expires_at")
    private Instant emailVerificationExpiresAt;

    @Column(name = "email_verification_sent_at")
    private Instant emailVerificationSentAt;

    @Column(name = "session_epoch", nullable = false)
    private long sessionEpoch;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "auth_user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<RoleEntity> roles = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected AuthUserEntity() {
        // for JPA
    }

    public AuthUserEntity(UUID id,
                          String email,
                          String passwordHash,
                          AuthUserStatus status,
                          Instant statusChangedAt,
                          boolean emailVerified,
                          Instant emailVerifiedAt,
                          String emailVerificationTokenHash,
                          Instant emailVerificationExpiresAt,
                          Instant emailVerificationSentAt,
                          long sessionEpoch,
                          Set<RoleEntity> roles,
                          Instant createdAt,
                          Long version) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = status;
        this.statusChangedAt = statusChangedAt;
        this.emailVerified = emailVerified;
        this.emailVerifiedAt = emailVerifiedAt;
        this.emailVerificationTokenHash = emailVerificationTokenHash;
        this.emailVerificationExpiresAt = emailVerificationExpiresAt;
        this.emailVerificationSentAt = emailVerificationSentAt;
        this.sessionEpoch = sessionEpoch;
        this.roles = roles;
        this.createdAt = createdAt;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public AuthUserStatus getStatus() {
        return status;
    }

    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public String getEmailVerificationTokenHash() {
        return emailVerificationTokenHash;
    }

    public Instant getEmailVerificationExpiresAt() {
        return emailVerificationExpiresAt;
    }

    public Instant getEmailVerificationSentAt() {
        return emailVerificationSentAt;
    }

    public long getSessionEpoch() {
        return sessionEpoch;
    }

    public Set<RoleEntity> getRoles() {
        return roles;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }
}
