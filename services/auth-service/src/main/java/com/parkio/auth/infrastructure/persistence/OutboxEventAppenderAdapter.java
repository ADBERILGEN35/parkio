package com.parkio.auth.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.event.UserRestoredEvent;
import com.parkio.auth.application.event.UserSuspendedEvent;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.domain.event.UserErasureRequestedEvent;
import com.parkio.auth.domain.event.UserRegisteredEvent;
import com.parkio.auth.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.auth.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import java.util.UUID;
import com.parkio.platform.tracing.KafkaTraceContextSupport;
import org.springframework.stereotype.Component;

/**
 * Writes domain events into the transactional outbox. Because the surrounding
 * use case is {@code @Transactional}, this insert commits atomically with the
 * registration (ai-context/06). {@code AuthOutboxRelay} publishes unpublished
 * rows to Kafka.
 */
@Component
public class OutboxEventAppenderAdapter implements OutboxEventAppender {

    private static final String AUTH_USER_AGGREGATE = "AuthUser";

    private final OutboxEventJpaRepository jpa;
    private final ObjectMapper objectMapper;

    public OutboxEventAppenderAdapter(OutboxEventJpaRepository jpa, ObjectMapper objectMapper) {
        this.jpa = jpa;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(UserRegisteredEvent event) {
        appendInternal(
                event.eventId(),
                UserRegisteredEvent.AGGREGATE_TYPE,
                event.userId(),
                UserRegisteredEvent.TYPE,
                event,
                event.occurredAt());
    }

    @Override
    public void append(UserSuspendedEvent event) {
        appendInternal(
                event.eventId(),
                AUTH_USER_AGGREGATE,
                event.userId(),
                UserSuspendedEvent.TYPE,
                event,
                event.occurredAt());
    }

    @Override
    public void append(UserRestoredEvent event) {
        appendInternal(
                event.eventId(),
                AUTH_USER_AGGREGATE,
                event.userId(),
                UserRestoredEvent.TYPE,
                event,
                event.occurredAt());
    }

    @Override
    public void append(UserErasureRequestedEvent event) {
        appendInternal(
                event.eventId(),
                UserErasureRequestedEvent.AGGREGATE_TYPE,
                event.erasureRequestId(),
                UserErasureRequestedEvent.TYPE,
                event,
                event.occurredAt());
    }

    private void appendInternal(
            UUID eventId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Object payload,
            java.time.Instant occurredAt) {
        OutboxEventEntity entity = new OutboxEventEntity(
                UUID.randomUUID(),
                eventId,
                aggregateType,
                aggregateId,
                eventType,
                serialize(payload, eventType),
                occurredAt,
                KafkaTraceContextSupport.currentOutboxTraceContext(),
                false);
        jpa.save(entity);
    }

    private String serialize(Object event, String eventType) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + eventType + " event", e);
        }
    }
}
