package com.parkio.gamification.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gamification.application.port.OutboxEventAppender;
import com.parkio.gamification.domain.event.GamificationEvent;
import com.parkio.gamification.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.gamification.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes domain events into the transactional outbox. Because the surrounding use
 * case is transactional, this insert commits atomically with the state change
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
    public void append(GamificationEvent event) {
        OutboxEventEntity entity = new OutboxEventEntity(
                UUID.randomUUID(),
                event.eventId(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                serialize(event),
                event.occurredAt(),
                false);
        jpa.save(entity);
    }

    private String serialize(GamificationEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + event.eventType() + " event", e);
        }
    }
}
