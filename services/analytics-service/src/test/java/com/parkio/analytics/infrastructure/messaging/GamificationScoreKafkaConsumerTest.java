package com.parkio.analytics.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.analytics.application.AnalyticsApplicationService;
import com.parkio.analytics.application.event.PointsEarnedEvent;
import com.parkio.analytics.application.event.UserLevelChangedEvent;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

/** Unit tests for the gamification→analytics consumer: dispatch, ignore, ack. */
class GamificationScoreKafkaConsumerTest {

    // Mirrors the Spring Boot ObjectMapper the consumer is injected with (event-contracts.md).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final AnalyticsApplicationService service = mock(AnalyticsApplicationService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final GamificationScoreKafkaConsumer consumer =
            new GamificationScoreKafkaConsumer(service, objectMapper);

    @Test
    void dispatchesPointsEarnedToHandlerAndAcks() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("userId", userId.toString());
        payload.put("points", 25);
        payload.put("sourceType", "PARKING_VERIFIED");
        payload.put("totalPoints", 125);
        payload.put("relatedEventId", UUID.randomUUID().toString());
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record(eventId, userId, "PointsEarned", payload), "PointsEarned", ack);

        ArgumentCaptor<PointsEarnedEvent> captor = ArgumentCaptor.forClass(PointsEarnedEvent.class);
        verify(service).handlePointsEarned(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo(eventId);
        assertThat(captor.getValue().userId()).isEqualTo(userId);
        verify(ack).acknowledge();
    }

    @Test
    void dispatchesUserLevelChangedToHandlerAndAcks() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("userId", userId.toString());
        payload.put("previousLevel", 1);
        payload.put("newLevel", 2);
        payload.put("totalPoints", 110);
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record(eventId, userId, "UserLevelChanged", payload), "UserLevelChanged", ack);

        ArgumentCaptor<UserLevelChangedEvent> captor = ArgumentCaptor.forClass(UserLevelChangedEvent.class);
        verify(service).handleUserLevelChanged(captor.capture());
        assertThat(captor.getValue().newLevel()).isEqualTo(2);
        verify(ack).acknowledge();
    }

    @Test
    void ignoresPointsDeductedButStillAcks() throws Exception {
        // analytics does not project PointsDeducted from this topic — ignore + ack.
        UUID userId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("userId", userId.toString());
        payload.put("points", 10);
        payload.put("sourceType", "PENALTY_FAKE");
        payload.put("totalPoints", 90);
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record(UUID.randomUUID(), userId, "PointsDeducted", payload), "PointsDeducted", ack);

        verify(service, never()).handlePointsEarned(any());
        verify(service, never()).handleUserLevelChanged(any());
        verify(ack).acknowledge();
    }

    private ConsumerRecord<String, String> record(UUID eventId, UUID userId, String eventType, ObjectNode payload)
            throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", "GamificationUser");
        envelope.put("aggregateId", userId.toString());
        envelope.put("occurredAt", "2026-06-08T12:00:00Z");
        envelope.put("version", 1);
        envelope.set("payload", payload);
        return new ConsumerRecord<>("parkio.gamification.score", 0, 0L,
                userId.toString(), objectMapper.writeValueAsString(envelope));
    }
}
