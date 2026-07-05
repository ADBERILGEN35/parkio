package com.parkio.moderation.application.port;

import java.time.Instant;
import java.util.UUID;

public interface InboxEventRepository {

    boolean tryClaim(UUID eventId, String eventType, Instant processedAt);
}
