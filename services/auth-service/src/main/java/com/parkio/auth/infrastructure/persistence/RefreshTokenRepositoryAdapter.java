package com.parkio.auth.infrastructure.persistence;

import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.domain.RefreshToken;
import com.parkio.auth.domain.RefreshTokenRevocationReason;
import com.parkio.auth.infrastructure.persistence.entity.RefreshTokenEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RefreshTokenJpaRepository;
import com.parkio.auth.infrastructure.persistence.mapper.AuthPersistenceMapper;
import java.time.Instant;
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
}
