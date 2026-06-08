package com.parkio.auth.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.auth.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.auth.infrastructure.persistence.jpa.OutboxEventJpaRepository;
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

/** Unit tests for the auth outbox relay: envelope/headers and publish-then-mark semantics. */
class AuthOutboxRelayTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-06-08T12:00:00Z");

    private final OutboxEventJpaRepository outbox = mock(OutboxEventJpaRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AuthOutboxRelay relay = new AuthOutboxRelay(outbox, kafkaTemplate, objectMapper, 100, 5000L);

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
    void leavesRowUnpublishedWhenSendFails() {
        OutboxEventEntity row = userRegisteredRow(java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThatThrownBy(relay::publishPending).isInstanceOf(IllegalStateException.class);

        assertThat(row.isPublished()).isFalse();
    }

    private static String headerValue(ProducerRecord<String, Object> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
