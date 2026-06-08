package com.parkio.notification.infrastructure.persistence;

import com.parkio.notification.application.port.DeviceTokenRepository;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.infrastructure.persistence.jpa.DeviceTokenJpaRepository;
import com.parkio.notification.infrastructure.persistence.mapper.NotificationPersistenceMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link DeviceTokenRepository} port to Spring Data JPA. */
@Component
public class DeviceTokenRepositoryAdapter implements DeviceTokenRepository {

    private final DeviceTokenJpaRepository jpa;

    public DeviceTokenRepositoryAdapter(DeviceTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public DeviceToken save(DeviceToken deviceToken) {
        return NotificationPersistenceMapper.toDomain(jpa.save(NotificationPersistenceMapper.toEntity(deviceToken)));
    }

    @Override
    public Optional<DeviceToken> findById(UUID id) {
        return jpa.findById(id).map(NotificationPersistenceMapper::toDomain);
    }

    @Override
    public Optional<DeviceToken> findByUserIdAndToken(UUID userId, String token) {
        return jpa.findByUserIdAndToken(userId, token).map(NotificationPersistenceMapper::toDomain);
    }
}
