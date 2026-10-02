package com.parkio.parking.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.platform.tracing.KafkaTraceContextSupport;
import com.parkio.parking.application.port.ErasureAckOutbox;
import com.parkio.parking.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.parking.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.parking.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes erasure ACKs into {@code outbox_events} inside the caller's erase transaction;
 * {@code ParkingOutboxRelay} publishes them after commit. An eventId already in the outbox is not
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
                serialize(event),
                event.occurredAt(),
                KafkaTraceContextSupport.currentOutboxTraceContext(),
                false));
    }

    private String serialize(UserErasureAcknowledgedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + UserErasureAcknowledgedEvent.TYPE + " event", e);
        }
    }
}
