package com.parkio.auth.infrastructure.persistence.entity;

import com.parkio.auth.domain.RefreshTokenRevocationReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code refresh_tokens} (stores only the token hash). */
@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "token_family_id", nullable = false, updatable = false)
    private UUID tokenFamilyId;

    @Column(name = "family_started_at", nullable = false, updatable = false)
    private Instant familyStartedAt;

    @Column(name = "parent_token_id", updatable = false)
    private UUID parentTokenId;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "reused_detected", nullable = false)
    private boolean reusedDetected;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 32)
    private RefreshTokenRevocationReason revokedReason;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected RefreshTokenEntity() {
        // for JPA
    }

    public RefreshTokenEntity(UUID id,
                              UUID userId,
                              String tokenHash,
                              Instant expiresAt,
                              UUID tokenFamilyId,
                              Instant familyStartedAt,
                              UUID parentTokenId,
                              boolean revoked,
                              boolean reusedDetected,
                              RefreshTokenRevocationReason revokedReason,
                              Instant revokedAt,
                              Long version) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.tokenFamilyId = tokenFamilyId;
        this.familyStartedAt = familyStartedAt;
        this.parentTokenId = parentTokenId;
        this.revoked = revoked;
        this.reusedDetected = reusedDetected;
        this.revokedReason = revokedReason;
        this.revokedAt = revokedAt;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getFamilyStartedAt() {
        return familyStartedAt;
    }

    public UUID getTokenFamilyId() {
        return tokenFamilyId;
    }

    public UUID getParentTokenId() {
        return parentTokenId;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public boolean isReusedDetected() {
        return reusedDetected;
    }

    public RefreshTokenRevocationReason getRevokedReason() {
        return revokedReason;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Long getVersion() {
        return version;
    }
}
