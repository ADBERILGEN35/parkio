package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ErasedUserTombstoneJpaRepository extends JpaRepository<ErasedUserTombstoneEntity, UUID> {
}
