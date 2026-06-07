package com.parkio.auth.infrastructure.persistence.jpa;

import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleJpaRepository extends JpaRepository<RoleEntity, UUID> {

    Optional<RoleEntity> findByName(RoleName name);
}
