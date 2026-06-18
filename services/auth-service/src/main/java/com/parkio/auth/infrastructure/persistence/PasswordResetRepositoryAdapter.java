package com.parkio.auth.infrastructure.persistence;

import com.parkio.auth.application.port.PasswordResetRepository;
import com.parkio.auth.domain.PasswordResetToken;
import com.parkio.auth.infrastructure.persistence.entity.PasswordResetTokenEntity;
import com.parkio.auth.infrastructure.persistence.jpa.PasswordResetTokenJpaRepository;
import com.parkio.auth.infrastructure.persistence.mapper.AuthPersistenceMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link PasswordResetRepository} port to Spring Data JPA. */
@Component
public class PasswordResetRepositoryAdapter implements PasswordResetRepository {

    private final PasswordResetTokenJpaRepository jpa;

    public PasswordResetRepositoryAdapter(PasswordResetTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public PasswordResetToken save(PasswordResetToken token) {
        PasswordResetTokenEntity saved = jpa.saveAndFlush(AuthPersistenceMapper.toEntity(token));
        return AuthPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<PasswordResetToken> findByTokenHash(String tokenHash) {
        return jpa.findByTokenHash(tokenHash).map(AuthPersistenceMapper::toDomain);
    }

    @Override
    public int consumeActiveForUser(UUID userId, Instant consumedAt) {
        return jpa.consumeActiveForUser(userId, consumedAt);
    }
}
