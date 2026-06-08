package com.parkio.parking.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.parking.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.parking.infrastructure.persistence.jpa.OutboxEventJpaRepository;
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

/** Unit tests for the parking outbox relay: envelope/headers/key/topic and publish-then-mark. */
class ParkingOutboxRelayTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-06-08T12:00:00Z");

    private final OutboxEventJpaRepository outbox = mock(OutboxEventJpaRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ParkingOutboxRelay relay = new ParkingOutboxRelay(outbox, kafkaTemplate, objectMapper, 100, 5000L);

    private OutboxEventEntity spotCreatedRow(UUID eventId, UUID spotId, UUID ownerId) {
        String payload = "{\"eventId\":\"" + eventId + "\",\"parkingSpotId\":\"" + spotId
                + "\",\"ownerUserId\":\"" + ownerId + "\",\"mediaId\":\"" + UUID.randomUUID()
                + "\",\"latitude\":41.0,\"longitude\":29.0,\"status\":\"ACTIVE\","
                + "\"occurredAt\":\"2026-06-08T12:00:00Z\"}";
        return new OutboxEventEntity(UUID.randomUUID(), eventId, "ParkingSpot", spotId,
                "ParkingSpotCreated", payload, OCCURRED_AT, false);
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
        assertThat(envelope.payload().get("ownerUserId").asText()).isEqualTo(ownerId.toString());

        assertThat(headerValue(sent, "eventId")).isEqualTo(eventId.toString());
        assertThat(headerValue(sent, "eventType")).isEqualTo("ParkingSpotCreated");
        assertThat(headerValue(sent, "aggregateType")).isEqualTo("ParkingSpot");
        assertThat(headerValue(sent, "aggregateId")).isEqualTo(spotId.toString());
        assertThat(headerValue(sent, "occurredAt")).isEqualTo(OCCURRED_AT.toString());
        assertThat(headerValue(sent, "version")).isEqualTo("1");

        assertThat(row.isPublished()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void leavesRowUnpublishedWhenSendFails() {
        OutboxEventEntity row = spotCreatedRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
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
