package com.parkio.aivalidation.infrastructure.messaging;

import com.parkio.platform.messaging.EventEnvelope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.aivalidation.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.aivalidation.infrastructure.persistence.jpa.OutboxEventJpaRepository;
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

/** Unit tests for the ai-validation outbox relay: envelope/headers/key/topic, publish-then-mark, DLQ. */
class AiValidationOutboxRelayTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-06-08T12:00:00Z");

    private final OutboxEventJpaRepository outbox = mock(OutboxEventJpaRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AiValidationOutboxRelay relay =
            new AiValidationOutboxRelay(outbox, kafkaTemplate, objectMapper, new SimpleMeterRegistry(), 100, 5000L, 3);

    private OutboxEventEntity resultRow(UUID eventId, UUID mediaId) {
        String payload = "{\"eventId\":\"" + eventId + "\",\"mediaId\":\"" + mediaId
                + "\",\"parkingSpotId\":null,\"status\":\"PASSED\",\"emptySpaceConfidence\":80,"
                + "\"legalRiskScore\":5,\"imageQualityScore\":90,\"aiConfidence\":88,"
                + "\"detectedRiskTypes\":[],\"occurredAt\":\"2026-06-08T12:00:00Z\"}";
        return new OutboxEventEntity(UUID.randomUUID(), eventId, "AiValidationResult", mediaId,
                "AiValidationCompleted", payload, OCCURRED_AT, false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesAiValidationCompletedToResultTopicKeyedByMediaId() {
        UUID eventId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        OutboxEventEntity row = resultRow(eventId, mediaId);
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, Object> sent = captor.getValue();

        assertThat(sent.topic()).isEqualTo("parkio.aivalidation.result");
        assertThat(sent.key()).isEqualTo(mediaId.toString());

        EventEnvelope envelope = (EventEnvelope) sent.value();
        assertThat(envelope.eventId()).isEqualTo(eventId);
        assertThat(envelope.eventType()).isEqualTo("AiValidationCompleted");
        assertThat(envelope.aggregateType()).isEqualTo("AiValidationResult");
        assertThat(envelope.aggregateId()).isEqualTo(mediaId);
        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.payload().get("status").asText()).isEqualTo("PASSED");

        assertThat(headerValue(sent, "eventType")).isEqualTo("AiValidationCompleted");
        assertThat(headerValue(sent, "aggregateId")).isEqualTo(mediaId.toString());
        assertThat(row.isPublished()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsFailureWithoutThrowingWhenSendFails() {
        OutboxEventEntity row = resultRow(UUID.randomUUID(), UUID.randomUUID());
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
        OutboxEventEntity poison = resultRow(UUID.randomUUID(), UUID.randomUUID());
        OutboxEventEntity healthy = resultRow(UUID.randomUUID(), UUID.randomUUID());
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(poison, healthy));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        relay.publishPending();

        assertThat(poison.isPublished()).isFalse();
        assertThat(healthy.isPublished()).isTrue();
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deadLettersRowAfterMaxAttempts() {
        OutboxEventEntity row = resultRow(UUID.randomUUID(), UUID.randomUUID());
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.publishPending();
        relay.publishPending();
        assertThat(row.isDeadLettered()).isFalse();
        relay.publishPending();

        assertThat(row.getFailureCount()).isEqualTo(3);
        assertThat(row.isDeadLettered()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesEveryPendingRowInOnePoll() {
        OutboxEventEntity a = resultRow(UUID.randomUUID(), UUID.randomUUID());
        OutboxEventEntity b = resultRow(UUID.randomUUID(), UUID.randomUUID());
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(a, b));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        relay.publishPending();

        // One poll drains the whole claimed batch; sends are dispatched (pipelined) then awaited.
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
        assertThat(a.isPublished()).isTrue();
        assertThat(b.isPublished()).isTrue();
    }

    private static String headerValue(ProducerRecord<String, Object> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
