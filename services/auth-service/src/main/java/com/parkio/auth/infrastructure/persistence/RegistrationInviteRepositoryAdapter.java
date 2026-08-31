package com.parkio.auth.infrastructure.persistence;

import com.parkio.auth.application.port.RegistrationInviteRepository;
import com.parkio.auth.domain.RegistrationInvite;
import com.parkio.auth.infrastructure.persistence.entity.RegistrationInviteEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RegistrationInviteJpaRepository;
import com.parkio.auth.infrastructure.persistence.mapper.AuthPersistenceMapper;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Adapts the {@link RegistrationInviteRepository} port to Spring Data JPA. */
@Component
public class RegistrationInviteRepositoryAdapter implements RegistrationInviteRepository {

    private final RegistrationInviteJpaRepository jpa;

    public RegistrationInviteRepositoryAdapter(RegistrationInviteJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public RegistrationInvite save(RegistrationInvite invite) {
        RegistrationInviteEntity saved = jpa.saveAndFlush(AuthPersistenceMapper.toEntity(invite));
        return AuthPersistenceMapper.toDomain(saved);
    }

    @Override
    public boolean consumeIfValid(String tokenHash, Instant now) {
        return jpa.consumeIfValid(tokenHash, now) == 1;
    }
}
