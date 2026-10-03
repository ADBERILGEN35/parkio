package com.parkio.analytics.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.platform.tracing.KafkaTraceContextSupport;
import com.parkio.analytics.application.port.ErasureAckOutbox;
import com.parkio.analytics.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.analytics.domain.event.UserErasureRestoreAcknowledgedEvent;
import com.parkio.analytics.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.analytics.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes erasure ACKs into {@code outbox_events} inside the caller's erase transaction;
 * {@code AnalyticsOutboxRelay} publishes them after commit. An eventId already in the outbox is not
 * appended again, so a redelivered erase command stays a no-op for the ACK instead of tripping
 * {@code uq_outbox_events_event_id} and rolling back the erase.
 */
@Component
public class ErasureAckOutboxAdapter implements ErasureAckOutbox {

    private final OutboxEventJpaRepository jpa;
    private final ObjectMapper objectMapper;

    public ErasureAckOutboxAdapter(OutboxEventJpaRepository jpa, ObjectMapper objectMapper) {
        this.jpa = jpa;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(UserErasureAcknowledgedEvent event) {
        if (jpa.existsByEventId(event.eventId())) {
            return;
        }
        jpa.save(new OutboxEventEntity(
                UUID.randomUUID(),
                event.eventId(),
                UserErasureAcknowledgedEvent.AGGREGATE_TYPE,
                event.erasureRequestId(),
                UserErasureAcknowledgedEvent.TYPE,
                serialize(event, UserErasureAcknowledgedEvent.TYPE),
                event.occurredAt(),
                KafkaTraceContextSupport.currentOutboxTraceContext(),
                false));
    }

    @Override
    public void appendRestoreAck(UserErasureRestoreAcknowledgedEvent event) {
        if (jpa.existsByEventId(event.eventId())) {
            return;
        }
        jpa.save(new OutboxEventEntity(
                UUID.randomUUID(),
                event.eventId(),
                UserErasureRestoreAcknowledgedEvent.AGGREGATE_TYPE,
                event.authUserId(),
                UserErasureRestoreAcknowledgedEvent.TYPE,
                serialize(event, UserErasureRestoreAcknowledgedEvent.TYPE),
                event.occurredAt(),
                KafkaTraceContextSupport.currentOutboxTraceContext(),
                false));
    }

    private String serialize(Object event, String type) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + type + " event", e);
        }
    }
}
