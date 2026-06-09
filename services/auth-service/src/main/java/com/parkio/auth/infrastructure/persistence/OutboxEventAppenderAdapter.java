package com.parkio.auth.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.domain.event.UserRegisteredEvent;
import com.parkio.auth.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.auth.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Writes domain events into the transactional outbox. Because the surrounding
 * use case is {@code @Transactional}, this insert commits atomically with the
 * registration (ai-context/06). A separate relay (not implemented yet) will
 * publish unpublished rows to Kafka.
 */
@Component
public class OutboxEventAppenderAdapter implements OutboxEventAppender {

    private final OutboxEventJpaRepository jpa;
    private final ObjectMapper objectMapper;

    public OutboxEventAppenderAdapter(OutboxEventJpaRepository jpa, ObjectMapper objectMapper) {
        this.jpa = jpa;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(UserRegisteredEvent event) {
        OutboxEventEntity entity = new OutboxEventEntity(
                UUID.randomUUID(),
                event.eventId(),
                UserRegisteredEvent.AGGREGATE_TYPE,
                event.userId(),
                UserRegisteredEvent.TYPE,
                serialize(event),
                event.occurredAt(),
                MDC.get("traceId"),
                false);
        jpa.save(entity);
    }

    private String serialize(UserRegisteredEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + UserRegisteredEvent.TYPE + " event", e);
        }
    }
}
