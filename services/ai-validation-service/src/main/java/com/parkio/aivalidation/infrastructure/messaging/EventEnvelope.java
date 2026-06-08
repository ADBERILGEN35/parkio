package com.parkio.aivalidation.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Local copy of the Kafka transport envelope (see {@code docs/architecture/kafka-transport.md}).
 * Used both by the relay (writes it for {@code AiValidationCompleted}) and the consumer
 * (reads it for media events). Infrastructure-only: it carries no business fields — the
 * business event is the opaque JSON {@code payload}. Duplicated locally; transport
 * contracts are not a shared module (ai-context/01). Unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        UUID eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        Instant occurredAt,
        Integer version,
        String traceId,
        JsonNode payload) {
}
