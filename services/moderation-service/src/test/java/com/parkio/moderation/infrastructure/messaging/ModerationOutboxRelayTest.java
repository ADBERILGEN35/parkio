package com.parkio.moderation.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.moderation.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.moderation.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** Unit tests for the moderation outbox relay: topic routing and publish-then-mark. */
class ModerationOutboxRelayTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-06-08T12:00:00Z");

    private final OutboxEventJpaRepository outbox = mock(OutboxEventJpaRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ModerationOutboxRelay relay = new ModerationOutboxRelay(outbox, kafkaTemplate, objectMapper, 100, 5000L);

    private OutboxEventEntity row(String aggregateType, UUID aggregateId, String eventType) {
        String payload = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-06-08T12:00:00Z\"}";
        return new OutboxEventEntity(UUID.randomUUID(), UUID.randomUUID(), aggregateType, aggregateId,
                eventType, payload, OCCURRED_AT, false);
    }

    @SuppressWarnings("unchecked")
    private void stubSuccessfulSend() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));
    }

    @Test
    void routesCaseEventsToModerationCaseTopic() {
        UUID caseId = UUID.randomUUID();
        OutboxEventEntity r = row("ModerationCase", caseId, "ModerationCaseResolved");
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(r));
        stubSuccessfulSend();

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("parkio.moderation.case");
        assertThat(captor.getValue().key()).isEqualTo(caseId.toString());
        assertThat(((EventEnvelope) captor.getValue().value()).eventType()).isEqualTo("ModerationCaseResolved");
        assertThat(r.isPublished()).isTrue();
    }

    @Test
    void routesActionEventsToModerationActionTopic() {
        UUID userId = UUID.randomUUID();
        OutboxEventEntity r = row("User", userId, "UserSuspended");
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(r));
        stubSuccessfulSend();

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("parkio.moderation.action");
        assertThat(captor.getValue().key()).isEqualTo(userId.toString());
        assertThat(r.isPublished()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void leavesRowUnpublishedWhenSendFails() {
        OutboxEventEntity r = row("ParkingSpot", UUID.randomUUID(), "ParkingSpotRejectedByModerator");
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(r));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThatThrownBy(relay::publishPending).isInstanceOf(IllegalStateException.class);

        assertThat(r.isPublished()).isFalse();
    }
}
