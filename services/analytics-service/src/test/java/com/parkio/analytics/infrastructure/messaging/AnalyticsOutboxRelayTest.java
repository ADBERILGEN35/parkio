package com.parkio.analytics.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.analytics.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.analytics.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import com.parkio.platform.messaging.EventEnvelope;
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

/** Unit tests for the analytics outbox relay (U05): ACK envelope/key/topic, publish-then-mark, DLQ. */
class AnalyticsOutboxRelayTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-06-08T12:00:00Z");

    private final OutboxEventJpaRepository outbox = mock(OutboxEventJpaRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AnalyticsOutboxRelay relay =
            new AnalyticsOutboxRelay(outbox, kafkaTemplate, objectMapper, new SimpleMeterRegistry(), 100, 5000L, 3);

    private OutboxEventEntity ackRow(UUID eventId, UUID requestId) {
        String payload = "{\"eventId\":\"" + eventId + "\",\"erasureRequestId\":\"" + requestId
                + "\",\"authUserId\":\"" + UUID.randomUUID() + "\",\"serviceName\":\"analytics\","
                + "\"status\":\"SUCCESS\",\"occurredAt\":\"2026-06-08T12:00:00Z\"}";
        return new OutboxEventEntity(UUID.randomUUID(), eventId, "AccountErasure", requestId,
                "UserErasureAcknowledged", payload, OCCURRED_AT, false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesErasureAckKeyedByRequestAndMarksItPublished() {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        OutboxEventEntity row = ackRow(eventId, requestId);
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, Object> sent = captor.getValue();
        assertThat(sent.topic()).isEqualTo("parkio.privacy.erasure");
        assertThat(sent.key()).isEqualTo(requestId.toString());
        EventEnvelope envelope = (EventEnvelope) sent.value();
        assertThat(envelope.eventId()).isEqualTo(eventId);
        assertThat(envelope.eventType()).isEqualTo("UserErasureAcknowledged");
        assertThat(envelope.aggregateType()).isEqualTo("AccountErasure");
        assertThat(envelope.aggregateId()).isEqualTo(requestId);
        assertThat(envelope.payload().get("serviceName").asText()).isEqualTo("analytics");
        assertThat(headerValue(sent, "eventType")).isEqualTo("UserErasureAcknowledged");
        assertThat(headerValue(sent, "eventId")).isEqualTo(eventId.toString());
        assertThat(row.isPublished()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedSendLeavesRowQueuedAndCountsTheAttempt() {
        OutboxEventEntity row = ackRow(UUID.randomUUID(), UUID.randomUUID());
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
    void rowIsDeadLetteredAfterMaxAttempts() {
        OutboxEventEntity row = ackRow(UUID.randomUUID(), UUID.randomUUID());
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.publishPending();
        relay.publishPending();
        relay.publishPending();

        assertThat(row.getFailureCount()).isEqualTo(3);
        assertThat(row.isDeadLettered()).isTrue();
        assertThat(row.isPublished()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void unroutableRowIsNeverSentAndCountsTowardDeadLettering() {
        OutboxEventEntity row = new OutboxEventEntity(UUID.randomUUID(), UUID.randomUUID(), "SomethingElse",
                UUID.randomUUID(), "SomethingHappened", "{}", OCCURRED_AT, false);
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));

        relay.publishPending();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        assertThat(row.getFailureCount()).isEqualTo(1);
        assertThat(row.isPublished()).isFalse();
    }

    private static String headerValue(ProducerRecord<String, Object> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
