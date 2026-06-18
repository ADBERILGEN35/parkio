package com.parkio.notification.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.notification.application.port.OutboxEventAppender;
import com.parkio.notification.domain.event.NotificationCreatedEvent;
import com.parkio.notification.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.notification.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Writes notification events into the transactional outbox. Because the surrounding
 * use case is transactional, this insert commits atomically with the state change
 * (ai-context/06). A separate relay (not implemented yet) will publish unpublished
 * rows to Kafka.
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
    public void append(NotificationCreatedEvent event) {
        OutboxEventEntity entity = new OutboxEventEntity(
                UUID.randomUUID(),
                event.eventId(),
                NotificationCreatedEvent.AGGREGATE_TYPE,
                event.notificationId(),
                NotificationCreatedEvent.TYPE,
                serialize(event),
                event.occurredAt(),
                MDC.get("correlationId"),
                false);
        jpa.save(entity);
    }

    private String serialize(NotificationCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + NotificationCreatedEvent.TYPE + " event", e);
        }
    }
}
