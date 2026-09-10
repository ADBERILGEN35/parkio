package com.parkio.gamification.infrastructure.persistence.jpa;

import com.parkio.gamification.infrastructure.persistence.entity.ErasedUserTombstoneEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ErasedUserTombstoneJpaRepository extends JpaRepository<ErasedUserTombstoneEntity, UUID> {
}
