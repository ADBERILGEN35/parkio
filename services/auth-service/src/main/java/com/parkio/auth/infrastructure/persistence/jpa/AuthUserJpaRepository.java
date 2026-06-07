package com.parkio.auth.infrastructure.persistence.jpa;

import com.parkio.auth.infrastructure.persistence.entity.AuthUserEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthUserJpaRepository extends JpaRepository<AuthUserEntity, UUID> {

    Optional<AuthUserEntity> findByEmail(String email);

    boolean existsByEmail(String email);
}
