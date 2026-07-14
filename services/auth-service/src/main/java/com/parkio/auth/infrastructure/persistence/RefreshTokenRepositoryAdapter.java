package com.parkio.auth.infrastructure.persistence;

import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.domain.RefreshToken;
import com.parkio.auth.domain.RefreshTokenRevocationReason;
import com.parkio.auth.infrastructure.persistence.entity.RefreshTokenEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RefreshTokenJpaRepository;
import com.parkio.auth.infrastructure.persistence.mapper.AuthPersistenceMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link RefreshTokenRepository} port to Spring Data JPA. */
@Component
public class RefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpa;

    public RefreshTokenRepositoryAdapter(RefreshTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public RefreshToken save(RefreshToken token) {
        RefreshTokenEntity saved = jpa.saveAndFlush(AuthPersistenceMapper.toEntity(token));
        return AuthPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return jpa.findByTokenHash(tokenHash).map(AuthPersistenceMapper::toDomain);
    }

    @Override
    public int revokeActiveFamily(
            UUID tokenFamilyId,
            RefreshTokenRevocationReason reason,
            Instant revokedAt) {
        return jpa.revokeActiveFamily(tokenFamilyId, reason, revokedAt);
    }

    @Override
    public int revokeAllActiveForUser(
            UUID userId,
            RefreshTokenRevocationReason reason,
            Instant revokedAt) {
        return jpa.revokeAllActiveForUser(userId, reason, revokedAt);
    }

    @Override
    public List<RefreshToken> findActiveSessionsForUser(UUID userId, Instant now) {
        return jpa.findActiveSessionsForUser(userId, now).stream()
                .map(AuthPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public long countActiveForUser(UUID userId, Instant now) {
        return jpa.countActiveForUser(userId, now);
    }

    @Override
    public long countAllActive(Instant now) {
        return jpa.countAllActive(now);
    }

    @Override
    public long countReuseDetected() {
        return jpa.countByReusedDetectedTrue();
    }

    @Override
    public Optional<RefreshToken> findByIdAndUserId(UUID sessionId, UUID userId) {
        return jpa.findByIdAndUserId(sessionId, userId).map(AuthPersistenceMapper::toDomain);
    }

    @Override
    public boolean revokeById(
            UUID sessionId,
            UUID userId,
            RefreshTokenRevocationReason reason,
            Instant revokedAt) {
        return jpa.revokeById(sessionId, userId, reason, revokedAt) > 0;
    }
}
