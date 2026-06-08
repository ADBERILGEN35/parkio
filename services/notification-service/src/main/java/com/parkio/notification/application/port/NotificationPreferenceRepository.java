package com.parkio.notification.application.port;

import com.parkio.notification.domain.NotificationPreference;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link NotificationPreference}. */
public interface NotificationPreferenceRepository {

    NotificationPreference save(NotificationPreference preference);

    Optional<NotificationPreference> findByUserId(UUID userId);
}
