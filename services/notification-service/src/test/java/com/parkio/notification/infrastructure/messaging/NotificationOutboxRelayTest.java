package com.parkio.notification.infrastructure.messaging;

import com.parkio.platform.messaging.EventEnvelope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.notification.infrastructure.persistence.entity.OutboxEventEntity;
import com.parkio.notification.infrastructure.persistence.jpa.OutboxEventJpaRepository;
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

class NotificationOutboxRelayTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-06-08T12:00:00Z");

    private final OutboxEventJpaRepository outbox = mock(OutboxEventJpaRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final NotificationOutboxRelay relay =
            new NotificationOutboxRelay(outbox, kafkaTemplate, objectMapper, new SimpleMeterRegistry(), 100, 5000L, 3);

    private OutboxEventEntity notificationCreatedRow(UUID eventId, UUID notificationId) {
        String payload = "{\"eventId\":\"" + eventId + "\",\"notificationId\":\"" + notificationId
                + "\",\"userId\":\"" + UUID.randomUUID() + "\",\"notificationType\":\"POINT_EARNED\","
                + "\"channel\":\"IN_APP\",\"occurredAt\":\"2026-06-08T12:00:00Z\"}";
        return new OutboxEventEntity(UUID.randomUUID(), eventId, "Notification", notificationId,
                "NotificationCreated", payload, OCCURRED_AT, false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsEnvelopeAndPublishesToNotificationTopic() {
        UUID eventId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        OutboxEventEntity row = notificationCreatedRow(eventId, notificationId);
        when(outbox.findUnpublishedBatchForUpdate(100)).thenReturn(List.of(row));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.<SendResult<String, Object>>completedFuture(null));

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, Object> sent = captor.getValue();

        assertThat(sent.topic()).isEqualTo("parkio.notification.notification");
        assertThat(sent.key()).isEqualTo(notificationId.toString());
        assertThat(sent.value()).isInstanceOf(EventEnvelope.class);
        assertThat(((EventEnvelope) sent.value()).eventType()).isEqualTo("NotificationCreated");
        assertThat(row.isPublished()).isTrue();
    }

    @Test
    void topicForNotificationAggregateType() {
        assertThat(NotificationOutboxRelay.topicFor("Notification"))
                .isEqualTo("parkio.notification.notification");
        assertThat(NotificationOutboxRelay.topicFor("Unknown")).isNull();
    }
}