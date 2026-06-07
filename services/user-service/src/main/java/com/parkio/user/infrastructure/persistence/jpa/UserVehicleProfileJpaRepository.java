package com.parkio.user.infrastructure.persistence.jpa;

import com.parkio.user.infrastructure.persistence.entity.UserVehicleProfileEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserVehicleProfileJpaRepository extends JpaRepository<UserVehicleProfileEntity, UUID> {

    Optional<UserVehicleProfileEntity> findByUserProfileId(UUID userProfileId);
}
