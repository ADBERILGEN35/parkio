package com.parkio.auth.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.auth.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.auth.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** Unit tests for the auth outbox relay: envelope/headers, publish-then-mark, and DLQ semantics. */
class AuthOutboxRelayTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-06-08T12:00:00Z");

    private final OutboxEventJpaRepository outbox = mock(OutboxEventJpaRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AuthOutboxRelay relay =
            new AuthOutboxRelay(outbox, kafkaTemplate, objectMapper, new SimpleMeterRegistry(), 100, 5000L, 3);

    private OutboxEventEntity userRegisteredRow(java.util.UUID eventId, java.util.UUID userId) {
        String payload = "{\"eventId\":\"" + eventId + "\",\"userId\":\"" + userId
                + "\",\"email\":\"rider@parkio.example\",\"occurredAt\":\"2026-06-08T12:00:00Z\"}";
        return new OutboxEventEntity(java.util.UUID.randomUUID(), eventId, "AuthUser", userId,
                "UserRegistered", payload, OCCURRED_AT, false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsEnvelopeAndHeadersAndPublishesKeyedByAggregateId() {
        java.util.UUID eventId = java.util.UUID.randomUUID();
        java.util.UUID userId = java.util.UUID.randomUUID();
        OutboxEventEntity row = userRegisteredRow(eventId, userId);
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, Object>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, Object> sent = captor.getValue();

        assertThat(sent.topic()).isEqualTo("parkio.auth.user");
        assertThat(sent.key()).isEqualTo(userId.toString());

        assertThat(sent.value()).isInstanceOf(EventEnvelope.class);
        EventEnvelope envelope = (EventEnvelope) sent.value();
        assertThat(envelope.eventId()).isEqualTo(eventId);
        assertThat(envelope.eventType()).isEqualTo("UserRegistered");
        assertThat(envelope.aggregateType()).isEqualTo("AuthUser");
        assertThat(envelope.aggregateId()).isEqualTo(userId);
        assertThat(envelope.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.payload().get("email").asText()).isEqualTo("rider@parkio.example");

        assertThat(headerValue(sent, "eventId")).isEqualTo(eventId.toString());
        assertThat(headerValue(sent, "eventType")).isEqualTo("UserRegistered");
        assertThat(headerValue(sent, "aggregateType")).isEqualTo("AuthUser");
        assertThat(headerValue(sent, "aggregateId")).isEqualTo(userId.toString());
        assertThat(headerValue(sent, "occurredAt")).isEqualTo(OCCURRED_AT.toString());
        assertThat(headerValue(sent, "version")).isEqualTo("1");

        // Row marked published only after the successful send.
        assertThat(row.isPublished()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsFailureWithoutThrowingWhenSendFails() {
        OutboxEventEntity row = userRegisteredRow(java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        // A single poison/transient failure must not abort the poll (no exception escapes).
        relay.publishPending();

        assertThat(row.isPublished()).isFalse();
        assertThat(row.getFailureCount()).isEqualTo(1);
        assertThat(row.isDeadLettered()).isFalse();
        assertThat(row.getLastFailureReason()).contains("broker down");
        assertThat(row.getLastFailedAt()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void continuesPublishingLaterRowsAfterOnePoisonRow() {
        OutboxEventEntity poison = userRegisteredRow(java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        OutboxEventEntity healthy = userRegisteredRow(java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(poison, healthy));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        relay.publishPending();

        assertThat(poison.isPublished()).isFalse();
        assertThat(poison.getFailureCount()).isEqualTo(1);
        // The poison row did not block the later row.
        assertThat(healthy.isPublished()).isTrue();
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deadLettersRowAfterMaxAttempts() {
        OutboxEventEntity row = userRegisteredRow(java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        // maxAttempts = 3 (see relay construction): the third failure dead-letters the row.
        relay.publishPending();
        relay.publishPending();
        assertThat(row.isDeadLettered()).isFalse();
        relay.publishPending();

        assertThat(row.getFailureCount()).isEqualTo(3);
        assertThat(row.isDeadLettered()).isTrue();
        assertThat(row.isPublished()).isFalse();
    }

    @Test
    void deadLettersUnroutableRowAfterMaxAttempts() {
        OutboxEventEntity row = new OutboxEventEntity(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "AuthUser", java.util.UUID.randomUUID(), "UnknownEvent", "{}", OCCURRED_AT, false);
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));

        relay.publishPending();
        relay.publishPending();
        relay.publishPending();

        assertThat(row.getFailureCount()).isEqualTo(3);
        assertThat(row.isDeadLettered()).isTrue();
        assertThat(row.getLastFailureReason()).contains("No topic mapping");
    }

    private static String headerValue(ProducerRecord<String, Object> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
