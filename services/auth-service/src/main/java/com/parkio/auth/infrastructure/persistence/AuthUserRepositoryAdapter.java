package com.parkio.auth.infrastructure.persistence;

import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.infrastructure.persistence.entity.AuthUserEntity;
import com.parkio.auth.infrastructure.persistence.jpa.AuthUserJpaRepository;
import com.parkio.auth.infrastructure.persistence.mapper.AuthPersistenceMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link AuthUserRepository} port to Spring Data JPA. */
@Component
public class AuthUserRepositoryAdapter implements AuthUserRepository {

    private final AuthUserJpaRepository jpa;

    public AuthUserRepositoryAdapter(AuthUserJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public AuthUser save(AuthUser user) {
        AuthUserEntity saved = jpa.save(AuthPersistenceMapper.toEntity(user));
        return AuthPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<AuthUser> findById(UUID id) {
        return jpa.findById(id).map(AuthPersistenceMapper::toDomain);
    }

    @Override
    public Optional<AuthUser> findByEmail(String email) {
        return jpa.findByEmail(email).map(AuthPersistenceMapper::toDomain);
    }

    @Override
    public Optional<AuthUser> findByEmailVerificationTokenHash(String tokenHash) {
        return jpa.findByEmailVerificationTokenHash(tokenHash).map(AuthPersistenceMapper::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpa.existsByEmail(email);
    }
}
