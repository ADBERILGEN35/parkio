package com.parkio.user.application.port;

import com.parkio.user.domain.PendingUserStatusEvent;
import java.util.List;
import java.util.UUID;

/**
 * Persistence port for moderation status events that arrived before the user's
 * profile existed. Saving is idempotent on the event id (redelivery-safe); rows
 * are deleted once the profile is provisioned and the latest event is applied.
 */
public interface PendingUserStatusEventRepository {

    void save(PendingUserStatusEvent event);

    List<PendingUserStatusEvent> findByAuthUserId(UUID authUserId);

    void deleteByAuthUserId(UUID authUserId);
}
