package com.parkio.parking.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.parking.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.parking.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** Unit tests for the parking outbox relay: envelope/headers/key/topic, publish-then-mark, DLQ. */
class ParkingOutboxRelayTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-06-08T12:00:00Z");

    private final OutboxEventJpaRepository outbox = mock(OutboxEventJpaRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ParkingOutboxRelay relay =
            new ParkingOutboxRelay(outbox, kafkaTemplate, objectMapper, new SimpleMeterRegistry(), 100, 5000L, 3);

    private OutboxEventEntity spotCreatedRow(UUID eventId, UUID spotId, UUID ownerId) {
        String payload = "{\"eventId\":\"" + eventId + "\",\"parkingSpotId\":\"" + spotId
                + "\",\"ownerUserId\":\"" + ownerId + "\",\"mediaId\":\"" + UUID.randomUUID()
                + "\",\"latitude\":41.0,\"longitude\":29.0,\"status\":\"ACTIVE\","
                + "\"occurredAt\":\"2026-06-08T12:00:00Z\"}";
        return new OutboxEventEntity(UUID.randomUUID(), eventId, "ParkingSpot", spotId,
                "ParkingSpotCreated", payload, OCCURRED_AT, "trace-relay-123", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsEnvelopeAndHeadersAndPublishesKeyedBySpotId() {
        UUID eventId = UUID.randomUUID();
        UUID spotId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        OutboxEventEntity row = spotCreatedRow(eventId, spotId, ownerId);
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, Object> sent = captor.getValue();

        assertThat(sent.topic()).isEqualTo("parkio.parking.spot");
        assertThat(sent.key()).isEqualTo(spotId.toString());

        assertThat(sent.value()).isInstanceOf(EventEnvelope.class);
        EventEnvelope envelope = (EventEnvelope) sent.value();
        assertThat(envelope.eventId()).isEqualTo(eventId);
        assertThat(envelope.eventType()).isEqualTo("ParkingSpotCreated");
        assertThat(envelope.aggregateType()).isEqualTo("ParkingSpot");
        assertThat(envelope.aggregateId()).isEqualTo(spotId);
        assertThat(envelope.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.traceId()).isEqualTo("trace-relay-123");
        assertThat(envelope.payload().get("ownerUserId").asText()).isEqualTo(ownerId.toString());

        assertThat(headerValue(sent, "eventId")).isEqualTo(eventId.toString());
        assertThat(headerValue(sent, "eventType")).isEqualTo("ParkingSpotCreated");
        assertThat(headerValue(sent, "aggregateType")).isEqualTo("ParkingSpot");
        assertThat(headerValue(sent, "aggregateId")).isEqualTo(spotId.toString());
        assertThat(headerValue(sent, "occurredAt")).isEqualTo(OCCURRED_AT.toString());
        assertThat(headerValue(sent, "version")).isEqualTo("1");
        assertThat(headerValue(sent, "traceId")).isEqualTo("trace-relay-123");

        assertThat(row.isPublished()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesStoredW3cTraceContextAsKafkaHeadersWithoutChangingEnvelopeTraceId() {
        UUID eventId = UUID.randomUUID();
        UUID spotId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String storedTraceContext = String.join("\n",
                "traceparent=00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "tracestate=vendor=value",
                "baggage=tenant=parkio",
                "correlationId=correlation-123");
        OutboxEventEntity row = spotCreatedRow(eventId, spotId, ownerId);
        row = new OutboxEventEntity(row.getId(), row.getEventId(), row.getAggregateType(), row.getAggregateId(),
                row.getEventType(), row.getPayload(), row.getOccurredAt(), storedTraceContext, false);
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, Object> sent = captor.getValue();
        EventEnvelope envelope = (EventEnvelope) sent.value();

        assertThat(envelope.traceId()).isEqualTo("correlation-123");
        assertThat(headerValue(sent, "traceId")).isEqualTo("correlation-123");
        assertThat(headerValue(sent, "traceparent"))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(headerValue(sent, "tracestate")).isEqualTo("vendor=value");
        assertThat(headerValue(sent, "baggage")).isEqualTo("tenant=parkio");
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsFailureWithoutThrowingWhenSendFails() {
        OutboxEventEntity row = spotCreatedRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.publishPending();

        assertThat(row.isPublished()).isFalse();
        assertThat(row.getFailureCount()).isEqualTo(1);
        assertThat(row.isDeadLettered()).isFalse();
        assertThat(row.getLastFailureReason()).contains("broker down");
    }

    @Test
    @SuppressWarnings("unchecked")
    void continuesPublishingLaterRowsAfterOnePoisonRow() {
        OutboxEventEntity poison = spotCreatedRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        OutboxEventEntity healthy = spotCreatedRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(poison, healthy));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        relay.publishPending();

        assertThat(poison.isPublished()).isFalse();
        assertThat(poison.getFailureCount()).isEqualTo(1);
        assertThat(healthy.isPublished()).isTrue();
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deadLettersRowAfterMaxAttempts() {
        OutboxEventEntity row = spotCreatedRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.publishPending();
        relay.publishPending();
        assertThat(row.isDeadLettered()).isFalse();
        relay.publishPending();

        assertThat(row.getFailureCount()).isEqualTo(3);
        assertThat(row.isDeadLettered()).isTrue();
        assertThat(row.isPublished()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesEveryPendingRowInOnePoll() {
        OutboxEventEntity a = spotCreatedRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        OutboxEventEntity b = spotCreatedRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        OutboxEventEntity c = spotCreatedRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(a, b, c));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        relay.publishPending();

        // One poll drains the whole claimed batch; all sends are dispatched (pipelined).
        verify(kafkaTemplate, times(3)).send(any(ProducerRecord.class));
        assertThat(a.isPublished()).isTrue();
        assertThat(b.isPublished()).isTrue();
        assertThat(c.isPublished()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchesSendsInClaimOrder() {
        OutboxEventEntity first = spotCreatedRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        OutboxEventEntity second = spotCreatedRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        // Repository returns rows oldest-first (created_at, id); the relay must preserve that order.
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(2)).send(captor.capture());
        List<ProducerRecord<String, Object>> sent = captor.getAllValues();
        assertThat(sent.get(0).key()).isEqualTo(first.getAggregateId().toString());
        assertThat(sent.get(1).key()).isEqualTo(second.getAggregateId().toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsSuccessDurationAndBatchSizeMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ParkingOutboxRelay metered =
                new ParkingOutboxRelay(outbox, kafkaTemplate, objectMapper, registry, 100, 5000L, 3);
        OutboxEventEntity a = spotCreatedRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        OutboxEventEntity b = spotCreatedRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(a, b));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        metered.publishPending();

        assertThat(registry.get("parkio.outbox.publish.success").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("parkio.outbox.publish.duration").timer().count()).isEqualTo(2L);
        assertThat(registry.get("parkio.outbox.batch.size").summary().totalAmount()).isEqualTo(2.0);
    }

    private static String headerValue(ProducerRecord<String, Object> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
