package com.parkio.aivalidation.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.aivalidation.application.port.OutboxEventAppender;
import com.parkio.aivalidation.domain.event.AiValidationCompletedEvent;
import com.parkio.aivalidation.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.aivalidation.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import java.util.UUID;
import com.parkio.platform.tracing.KafkaTraceContextSupport;
import org.springframework.stereotype.Component;

/**
 * Writes the advisory completion event into the transactional outbox. Because the
 * surrounding use case is transactional, this insert commits atomically with the
 * result (ai-context/06). A separate relay (not implemented yet) will publish
 * unpublished rows to Kafka.
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
    public void append(AiValidationCompletedEvent event) {
        OutboxEventEntity entity = new OutboxEventEntity(
                UUID.randomUUID(),
                event.eventId(),
                AiValidationCompletedEvent.AGGREGATE_TYPE,
                event.aggregateId(),
                AiValidationCompletedEvent.TYPE,
                serialize(event),
                event.occurredAt(),
                KafkaTraceContextSupport.currentOutboxTraceContext(),
                false);
        jpa.save(entity);
    }

    private String serialize(AiValidationCompletedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + AiValidationCompletedEvent.TYPE + " event", e);
        }
    }
}
