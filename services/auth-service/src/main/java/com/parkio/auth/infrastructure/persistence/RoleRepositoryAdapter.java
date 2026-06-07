package com.parkio.auth.infrastructure.persistence;

import com.parkio.auth.application.port.RoleRepository;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import com.parkio.auth.infrastructure.persistence.mapper.AuthPersistenceMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Adapts the {@link RoleRepository} port to Spring Data JPA. */
@Component
public class RoleRepositoryAdapter implements RoleRepository {

    private final RoleJpaRepository jpa;

    public RoleRepositoryAdapter(RoleJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Role> findByName(RoleName name) {
        return jpa.findByName(name).map(AuthPersistenceMapper::toDomain);
    }
}
