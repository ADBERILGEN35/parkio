package com.parkio.notification.infrastructure.persistence.jpa;

import com.parkio.notification.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ErasedUserTombstoneJpaRepository extends JpaRepository<ErasedUserTombstoneEntity, UUID> {
}
