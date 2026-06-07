package com.parkio.auth.infrastructure.persistence.jpa;

import com.parkio.auth.infrastructure.persistence.entity.RefreshTokenEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);
}
