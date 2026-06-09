package com.parkio.user.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.user.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.user.infrastructure.persistence.jpa.OutboxEventJpaRepository;
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

/** Unit tests for the user outbox relay: envelope/headers/key/topic and publish-then-mark. */
class UserOutboxRelayTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-06-09T12:00:00Z");

    private final OutboxEventJpaRepository outbox = mock(OutboxEventJpaRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final UserOutboxRelay relay = new UserOutboxRelay(outbox, kafkaTemplate, objectMapper, 100, 5000L);

    private OutboxEventEntity profileCreatedRow(UUID eventId, UUID authUserId, UUID profileId) {
        String payload = "{\"eventId\":\"" + eventId + "\",\"userProfileId\":\"" + profileId
                + "\",\"authUserId\":\"" + authUserId + "\",\"occurredAt\":\"2026-06-09T12:00:00Z\"}";
        return new OutboxEventEntity(UUID.randomUUID(), eventId, "UserProfile", authUserId,
                "UserProfileCreated", payload, OCCURRED_AT, false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsEnvelopeAndHeadersAndPublishesKeyedByAuthUserId() {
        UUID eventId = UUID.randomUUID();
        UUID authUserId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        OutboxEventEntity row = profileCreatedRow(eventId, authUserId, profileId);
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, Object> sent = captor.getValue();

        assertThat(sent.topic()).isEqualTo("parkio.user.profile");
        assertThat(sent.key()).isEqualTo(authUserId.toString());

        assertThat(sent.value()).isInstanceOf(EventEnvelope.class);
        EventEnvelope envelope = (EventEnvelope) sent.value();
        assertThat(envelope.eventId()).isEqualTo(eventId);
        assertThat(envelope.eventType()).isEqualTo("UserProfileCreated");
        assertThat(envelope.aggregateType()).isEqualTo("UserProfile");
        assertThat(envelope.aggregateId()).isEqualTo(authUserId);
        assertThat(envelope.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.traceId()).isNull();
        assertThat(envelope.payload().get("userProfileId").asText()).isEqualTo(profileId.toString());

        assertThat(headerValue(sent, "eventId")).isEqualTo(eventId.toString());
        assertThat(headerValue(sent, "eventType")).isEqualTo("UserProfileCreated");
        assertThat(headerValue(sent, "aggregateType")).isEqualTo("UserProfile");
        assertThat(headerValue(sent, "aggregateId")).isEqualTo(authUserId.toString());
        assertThat(headerValue(sent, "occurredAt")).isEqualTo(OCCURRED_AT.toString());
        assertThat(headerValue(sent, "version")).isEqualTo("1");

        assertThat(row.isPublished()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void leavesRowUnpublishedWhenSendFails() {
        OutboxEventEntity row = profileCreatedRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
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
