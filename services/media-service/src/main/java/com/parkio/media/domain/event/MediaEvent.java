package com.parkio.media.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Common shape for media domain events written to the transactional outbox. The
 * outbox adapter reads {@link #aggregateType()}, {@link #aggregateId()} and
 * {@link #eventType()} and serializes the concrete event as the payload
 * (ai-context/06). Sealed so the set of media events is closed and explicit.
 */
public sealed interface MediaEvent permits MediaUploadedEvent, MediaRejectedEvent {

    String AGGREGATE_TYPE = "Media";

    UUID eventId();

    UUID aggregateId();

    String eventType();

    Instant occurredAt();

    default String aggregateType() {
        return AGGREGATE_TYPE;
    }
}
