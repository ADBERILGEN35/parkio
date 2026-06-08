package com.parkio.notification.application.port;

import com.parkio.notification.domain.NotificationTemplate;
import com.parkio.notification.domain.NotificationType;
import java.util.Optional;

/** Read port for the seeded {@link NotificationTemplate}s. */
public interface NotificationTemplateRepository {

    Optional<NotificationTemplate> findByType(NotificationType type);
}
