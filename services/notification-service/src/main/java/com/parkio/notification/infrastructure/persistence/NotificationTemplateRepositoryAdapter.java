package com.parkio.notification.infrastructure.persistence;

import com.parkio.notification.application.port.NotificationTemplateRepository;
import com.parkio.notification.domain.NotificationTemplate;
import com.parkio.notification.domain.NotificationType;
import com.parkio.notification.infrastructure.persistence.jpa.NotificationTemplateJpaRepository;
import com.parkio.notification.infrastructure.persistence.mapper.NotificationPersistenceMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Adapts the {@link NotificationTemplateRepository} port to Spring Data JPA. */
@Component
public class NotificationTemplateRepositoryAdapter implements NotificationTemplateRepository {

    private final NotificationTemplateJpaRepository jpa;

    public NotificationTemplateRepositoryAdapter(NotificationTemplateJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<NotificationTemplate> findByType(NotificationType type) {
        return jpa.findById(type).map(NotificationPersistenceMapper::toDomain);
    }
}
