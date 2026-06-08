package com.parkio.notification.infrastructure.persistence;

import com.parkio.notification.application.port.NotificationRepository;
import com.parkio.notification.domain.Notification;
import com.parkio.notification.infrastructure.persistence.jpa.NotificationJpaRepository;
import com.parkio.notification.infrastructure.persistence.mapper.NotificationPersistenceMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** Adapts the {@link NotificationRepository} port to Spring Data JPA. */
@Component
public class NotificationRepositoryAdapter implements NotificationRepository {

    private final NotificationJpaRepository jpa;

    public NotificationRepositoryAdapter(NotificationJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Notification save(Notification notification) {
        return NotificationPersistenceMapper.toDomain(jpa.save(NotificationPersistenceMapper.toEntity(notification)));
    }

    @Override
    public Optional<Notification> findById(UUID id) {
        return jpa.findById(id).map(NotificationPersistenceMapper::toDomain);
    }

    @Override
    public List<Notification> findRecentByUserId(UUID userId, int limit) {
        return jpa.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit)).stream()
                .map(NotificationPersistenceMapper::toDomain)
                .toList();
    }
}
