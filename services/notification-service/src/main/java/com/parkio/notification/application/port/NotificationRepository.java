package com.parkio.notification.application.port;

import com.parkio.notification.domain.Notification;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link Notification}. */
public interface NotificationRepository {

    Notification save(Notification notification);

    Optional<Notification> findById(UUID id);

    List<Notification> findRecentByUserId(UUID userId, int limit);
}
