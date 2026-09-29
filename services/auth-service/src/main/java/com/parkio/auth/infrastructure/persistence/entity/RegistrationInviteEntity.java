package com.parkio.auth.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code registration_invites} (stores only the token hash). */
@Entity
@Table(name = "registration_invites")
public class RegistrationInviteEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "token_hash", nullable = false, unique = true, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 200)
    private String createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected RegistrationInviteEntity() {
        // for JPA
    }

    public RegistrationInviteEntity(UUID id,
                                    String tokenHash,
                                    Instant expiresAt,
                                    Instant consumedAt,
                                    Instant createdAt,
                                    String createdBy,
                                    Long version) {
        this.id = id;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.consumedAt = consumedAt;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Long getVersion() {
        return version;
    }
}
