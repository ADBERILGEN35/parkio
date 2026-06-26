package com.parkio.moderation.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.moderation.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.moderation.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** Unit tests for the moderation outbox relay: topic routing, publish-then-mark, DLQ. */
class ModerationOutboxRelayTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-06-08T12:00:00Z");

    private final OutboxEventJpaRepository outbox = mock(OutboxEventJpaRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ModerationOutboxRelay relay =
            new ModerationOutboxRelay(outbox, kafkaTemplate, objectMapper, new SimpleMeterRegistry(), 100, 5000L, 3);

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
    void recordsFailureWithoutThrowingWhenSendFails() {
        OutboxEventEntity r = row("ParkingSpot", UUID.randomUUID(), "ParkingSpotRejectedByModerator");
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(r));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.publishPending();

        assertThat(r.isPublished()).isFalse();
        assertThat(r.getFailureCount()).isEqualTo(1);
        assertThat(r.isDeadLettered()).isFalse();
        assertThat(r.getLastFailureReason()).contains("broker down");
    }

    @Test
    @SuppressWarnings("unchecked")
    void continuesPublishingLaterRowsAfterOnePoisonRow() {
        OutboxEventEntity poison = row("User", UUID.randomUUID(), "UserSuspended");
        OutboxEventEntity healthy = row("ModerationCase", UUID.randomUUID(), "ModerationCaseResolved");
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
        OutboxEventEntity r = row("User", UUID.randomUUID(), "UserSuspended");
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(r));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.publishPending();
        relay.publishPending();
        assertThat(r.isDeadLettered()).isFalse();
        relay.publishPending();

        assertThat(r.getFailureCount()).isEqualTo(3);
        assertThat(r.isDeadLettered()).isTrue();
    }

    @Test
    void deadLettersUnroutableEventTypeAfterMaxAttempts() {
        OutboxEventEntity r = row("ModerationCase", UUID.randomUUID(), "UnknownModerationEvent");
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(r));

        relay.publishPending();
        relay.publishPending();
        relay.publishPending();

        assertThat(r.getFailureCount()).isEqualTo(3);
        assertThat(r.isDeadLettered()).isTrue();
        assertThat(r.getLastFailureReason()).contains("No topic mapping");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesEveryPendingRowInOnePoll() {
        OutboxEventEntity caseEvent = row("ModerationCase", UUID.randomUUID(), "ModerationCaseResolved");
        OutboxEventEntity actionEvent = row("User", UUID.randomUUID(), "UserSuspended");
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(caseEvent, actionEvent));
        stubSuccessfulSend();

        relay.publishPending();

        // One poll drains the whole claimed batch across both topics (pipelined dispatch).
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
        assertThat(caseEvent.isPublished()).isTrue();
        assertThat(actionEvent.isPublished()).isTrue();
    }
}
