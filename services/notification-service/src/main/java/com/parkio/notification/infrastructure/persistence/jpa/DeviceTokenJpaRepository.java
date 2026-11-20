package com.parkio.notification.infrastructure.persistence.jpa;

import com.parkio.notification.infrastructure.persistence.entity.DeviceTokenEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceTokenJpaRepository extends JpaRepository<DeviceTokenEntity, UUID> {

    Optional<DeviceTokenEntity> findByUserIdAndToken(UUID userId, String token);

    List<DeviceTokenEntity> findByUserIdAndActiveTrue(UUID userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update DeviceTokenEntity deviceToken
               set deviceToken.active = false,
                   deviceToken.updatedAt = :now,
                   deviceToken.version = deviceToken.version + 1
             where deviceToken.token = :token
               and deviceToken.userId <> :userId
               and deviceToken.active = true
            """)
    int deactivateActiveForOtherUsers(
            @Param("token") String token,
            @Param("userId") UUID userId,
            @Param("now") Instant now);
}
