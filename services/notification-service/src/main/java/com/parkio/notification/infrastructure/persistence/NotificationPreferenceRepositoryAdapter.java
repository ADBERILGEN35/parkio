package com.parkio.notification.infrastructure.persistence;

import com.parkio.notification.application.port.NotificationPreferenceRepository;
import com.parkio.notification.domain.NotificationPreference;
import com.parkio.notification.infrastructure.persistence.jpa.NotificationPreferenceJpaRepository;
import com.parkio.notification.infrastructure.persistence.mapper.NotificationPersistenceMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link NotificationPreferenceRepository} port to Spring Data JPA. */
@Component
public class NotificationPreferenceRepositoryAdapter implements NotificationPreferenceRepository {

    private final NotificationPreferenceJpaRepository jpa;

    public NotificationPreferenceRepositoryAdapter(NotificationPreferenceJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public NotificationPreference save(NotificationPreference preference) {
        return NotificationPersistenceMapper.toDomain(jpa.save(NotificationPersistenceMapper.toEntity(preference)));
    }

    @Override
    public Optional<NotificationPreference> findByUserId(UUID userId) {
        return jpa.findById(userId).map(NotificationPersistenceMapper::toDomain);
    }
}
