package com.parkio.auth.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of the Kafka transport envelope (see {@code docs/architecture/kafka-transport.md}).
 * Infrastructure-only: it carries no business fields — the business event is the opaque
 * JSON {@code payload} (the outbox row's payload, embedded verbatim). Duplicated locally;
 * transport contracts are not a shared module (ai-context/01).
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        Instant occurredAt,
        int version,
        String traceId,
        JsonNode payload) {

    public static final int CURRENT_VERSION = 1;
}
