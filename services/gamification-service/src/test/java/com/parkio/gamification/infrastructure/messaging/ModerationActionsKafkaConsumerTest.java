package com.parkio.gamification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.gamification.application.GamificationApplicationService;
import com.parkio.gamification.application.event.ParkingSpotRejectedByModeratorEvent;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

/** Unit tests for the moderation.action → gamification consumer: dispatch, ignore, ack. */
class ModerationActionsKafkaConsumerTest {

    // Mirrors the Spring Boot ObjectMapper the consumer is injected with (event-contracts.md).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final GamificationApplicationService service = mock(GamificationApplicationService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final ModerationActionsKafkaConsumer consumer = new ModerationActionsKafkaConsumer(service, objectMapper);

    @Test
    void dispatchesParkingSpotRejectedByModeratorAndAcks() throws Exception {
        UUID ownerId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("caseId", UUID.randomUUID().toString());
        payload.put("parkingSpotId", UUID.randomUUID().toString());
        payload.put("ownerUserId", ownerId.toString());
        payload.put("moderatorId", UUID.randomUUID().toString());
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record("ParkingSpotRejectedByModerator", payload), "ParkingSpotRejectedByModerator", ack);

        ArgumentCaptor<ParkingSpotRejectedByModeratorEvent> captor =
                ArgumentCaptor.forClass(ParkingSpotRejectedByModeratorEvent.class);
        verify(service).handleParkingSpotRejectedByModerator(captor.capture());
        assertThat(captor.getValue().ownerUserId()).isEqualTo(ownerId);
        verify(ack).acknowledge();
    }

    @Test
    void ignoresUserSuspendedButStillAcks() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("userId", UUID.randomUUID().toString());
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record("UserSuspended", payload), "UserSuspended", ack);

        verify(service, never()).handleParkingSpotRejectedByModerator(any());
        verify(ack).acknowledge();
    }

    private ConsumerRecord<String, String> record(String eventType, ObjectNode payload) throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", eventType.startsWith("Parking") ? "ParkingSpot" : "User");
        envelope.put("aggregateId", UUID.randomUUID().toString());
        envelope.put("occurredAt", "2026-06-08T12:00:00Z");
        envelope.put("version", 1);
        envelope.set("payload", payload);
        return new ConsumerRecord<>("parkio.moderation.action", 0, 0L,
                UUID.randomUUID().toString(), objectMapper.writeValueAsString(envelope));
    }
}
