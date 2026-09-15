package com.parkio.analytics.infrastructure.persistence.jpa;

import com.parkio.analytics.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ErasedUserTombstoneJpaRepository extends JpaRepository<ErasedUserTombstoneEntity, UUID> {
}
