package com.parkio.user.application.port;

import java.time.Instant;
import java.util.UUID;

public interface InboxEventRepository {

    boolean tryClaim(UUID eventId, String eventType, Instant processedAt);
}
