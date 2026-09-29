package com.parkio.auth.infrastructure.persistence.jpa;

import com.parkio.auth.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ErasedUserTombstoneJpaRepository extends JpaRepository<ErasedUserTombstoneEntity, UUID> {
}
