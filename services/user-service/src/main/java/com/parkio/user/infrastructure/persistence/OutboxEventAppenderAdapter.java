package com.parkio.user.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.user.application.port.OutboxEventAppender;
import com.parkio.user.domain.event.UserProfileCreatedEvent;
import com.parkio.user.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.user.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import java.util.UUID;
import com.parkio.platform.tracing.KafkaTraceContextSupport;
import org.springframework.stereotype.Component;

/**
 * Writes domain events into the transactional outbox. Because the surrounding
 * use case is {@code @Transactional}, this insert commits atomically with the
 * profile creation (ai-context/06). A separate relay (not implemented yet) will
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
    public void append(UserProfileCreatedEvent event) {
        OutboxEventEntity entity = new OutboxEventEntity(
                UUID.randomUUID(),
                event.eventId(),
                UserProfileCreatedEvent.AGGREGATE_TYPE,
                event.userProfileId(),
                UserProfileCreatedEvent.TYPE,
                serialize(event),
                event.occurredAt(),
                KafkaTraceContextSupport.currentOutboxTraceContext(),
                false);
        jpa.save(entity);
    }

    private String serialize(UserProfileCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + UserProfileCreatedEvent.TYPE + " event", e);
        }
    }
}
