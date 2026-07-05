package com.parkio.notification.application.port;

import java.time.Instant;
import java.util.UUID;

/** Processed-message dedupe store (inbox pattern). */
public interface InboxEventRepository {

    /**
     * Atomically claims an event for processing. Returns {@code true} when this caller
     * inserted the inbox row; {@code false} when another consumer already claimed it.
     */
    boolean tryClaim(UUID eventId, String eventType, Instant processedAt);
}
