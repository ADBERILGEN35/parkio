package com.parkio.aivalidation.application.port;

import java.time.Instant;
import java.util.UUID;

public interface InboxEventRepository {

    /** True when this event id was already claimed (processed). Read-only. */
    boolean alreadyProcessed(UUID eventId);

    /**
     * Atomically claims the event for processing. Returns {@code true} when this
     * caller inserted the row; {@code false} if another worker already claimed it.
     */
    boolean tryClaim(UUID eventId, String eventType, Instant processedAt);
}
