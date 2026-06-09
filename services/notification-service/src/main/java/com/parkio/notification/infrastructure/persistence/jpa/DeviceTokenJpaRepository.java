package com.parkio.notification.infrastructure.persistence.jpa;

import com.parkio.notification.infrastructure.persistence.entity.DeviceTokenEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTokenJpaRepository extends JpaRepository<DeviceTokenEntity, UUID> {

    Optional<DeviceTokenEntity> findByUserIdAndToken(UUID userId, String token);

    List<DeviceTokenEntity> findByUserIdAndActiveTrue(UUID userId);
}
