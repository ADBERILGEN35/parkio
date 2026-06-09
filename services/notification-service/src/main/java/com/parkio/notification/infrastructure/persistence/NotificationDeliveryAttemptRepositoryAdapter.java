package com.parkio.notification.infrastructure.persistence;

import com.parkio.notification.application.port.NotificationDeliveryAttemptRepository;
import com.parkio.notification.domain.NotificationChannel;
import com.parkio.notification.domain.NotificationDeliveryAttempt;
import com.parkio.notification.infrastructure.persistence.jpa.NotificationDeliveryAttemptJpaRepository;
import com.parkio.notification.infrastructure.persistence.mapper.NotificationPersistenceMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Adapts the {@link NotificationDeliveryAttemptRepository} port to Spring Data JPA. */
@Component
public class NotificationDeliveryAttemptRepositoryAdapter implements NotificationDeliveryAttemptRepository {

    private final NotificationDeliveryAttemptJpaRepository jpa;

    public NotificationDeliveryAttemptRepositoryAdapter(NotificationDeliveryAttemptJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public NotificationDeliveryAttempt save(NotificationDeliveryAttempt attempt) {
        return NotificationPersistenceMapper.toDomain(jpa.save(NotificationPersistenceMapper.toEntity(attempt)));
    }

    @Override
    public List<NotificationDeliveryAttempt> claimDue(Instant now, int limit) {
        return jpa.claimDueForUpdate(now, limit).stream()
                .map(NotificationPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public boolean existsByNotificationIdAndChannel(UUID notificationId, NotificationChannel channel) {
        return jpa.existsByNotificationIdAndChannel(notificationId, channel);
    }
}
