package com.parkio.auth.infrastructure.persistence.jpa;

import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.persistence.entity.AuthUserEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthUserJpaRepository
        extends JpaRepository<AuthUserEntity, UUID>, JpaSpecificationExecutor<AuthUserEntity> {

    Optional<AuthUserEntity> findByEmail(String email);

    Optional<AuthUserEntity> findByEmailVerificationTokenHash(String tokenHash);

    boolean existsByEmail(String email);

    long countByStatus(AuthUserStatus status);

    long countByEmailVerified(boolean emailVerified);

    long countByCreatedAtGreaterThanEqual(Instant since);

    @Query("SELECT COUNT(DISTINCT u.id) FROM AuthUserEntity u JOIN u.roles r WHERE r.name = :roleName")
    long countByRoleName(@Param("roleName") RoleName roleName);
}
