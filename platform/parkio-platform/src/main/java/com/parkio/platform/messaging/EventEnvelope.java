package com.parkio.platform.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Service-agnostic Kafka transport envelope.
 *
 * <p>The payload remains an opaque JSON node owned by the producing service's
 * event contract. This class intentionally does not model domain event payloads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
