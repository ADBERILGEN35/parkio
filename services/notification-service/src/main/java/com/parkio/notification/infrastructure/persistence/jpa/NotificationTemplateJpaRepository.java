package com.parkio.notification.infrastructure.persistence.jpa;

import com.parkio.notification.domain.NotificationType;
import com.parkio.notification.infrastructure.persistence.entity.NotificationTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationTemplateJpaRepository
        extends JpaRepository<NotificationTemplateEntity, NotificationType> {
}
