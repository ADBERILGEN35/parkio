package com.parkio.analytics.application.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Inbox port for idempotent event ingestion (ai-context/06). A processed event id is
 * recorded so redeliveries are skipped.
 */
public interface InboxEventRepository {

    boolean existsByEventId(UUID eventId);

    void markProcessed(UUID eventId, String eventType, Instant processedAt);
}
