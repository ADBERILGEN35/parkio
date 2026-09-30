package com.parkio.gamification.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gamification.application.port.OutboxEventAppender;
import com.parkio.gamification.domain.event.GamificationEvent;
import com.parkio.gamification.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.gamification.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import java.util.UUID;
import com.parkio.platform.tracing.KafkaTraceContextSupport;
import org.springframework.stereotype.Component;

/**
 * Writes domain events into the transactional outbox. Because the surrounding use
 * case is transactional, this insert commits atomically with the state change
 * (ai-context/06). {@code GamificationOutboxRelay} publishes unpublished
 * rows to Kafka. An event whose eventId is already in the outbox is not appended again, so a
 * redelivered command that re-derives the same eventId stays a no-op instead of tripping the
 * {@code uq_outbox_events_event_id} index and rolling back the caller.
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
        if (jpa.existsByEventId(event.eventId())) {
            return;
        }
        OutboxEventEntity entity = new OutboxEventEntity(
                UUID.randomUUID(),
                event.eventId(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                serialize(event),
                event.occurredAt(),
                KafkaTraceContextSupport.currentOutboxTraceContext(),
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
