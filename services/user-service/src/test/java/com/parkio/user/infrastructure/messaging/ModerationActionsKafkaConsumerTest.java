package com.parkio.user.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.user.application.UserApplicationService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/** Unit tests for the moderation.action → user consumer: dispatch, ignore, ack. */
class ModerationActionsKafkaConsumerTest {

    // Mirrors the Spring Boot ObjectMapper the consumer is injected with (event-contracts.md).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final UserApplicationService service = mock(UserApplicationService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final ModerationActionsKafkaConsumer consumer = new ModerationActionsKafkaConsumer(service, objectMapper);

    @Test
    void dispatchesUserSuspendedAndAcks() throws Exception {
        consumer.onMessage(record(UUID.randomUUID(), "UserSuspended"), "UserSuspended", ack);
        verify(service).handleUserSuspended(any());
        verify(ack).acknowledge();
    }

    @Test
    void dispatchesUserRestoredAndAcks() throws Exception {
        consumer.onMessage(record(UUID.randomUUID(), "UserRestored"), "UserRestored", ack);
        verify(service).handleUserRestored(any());
        verify(ack).acknowledge();
    }

    @Test
    void ignoresParkingSpotRejectedByModeratorButStillAcks() throws Exception {
        consumer.onMessage(record(UUID.randomUUID(), "ParkingSpotRejectedByModerator"),
                "ParkingSpotRejectedByModerator", ack);
        verify(service, never()).handleUserSuspended(any());
        verify(service, never()).handleUserRestored(any());
        verify(ack).acknowledge();
    }

    private ConsumerRecord<String, String> record(UUID userId, String eventType) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("caseId", UUID.randomUUID().toString());
        payload.put("userId", userId.toString());
        payload.put("moderatorId", UUID.randomUUID().toString());
        // ParkingSpotRejectedByModerator carries parkingSpotId instead of userId — tolerated.
        payload.put("parkingSpotId", UUID.randomUUID().toString());
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", "User");
        envelope.put("aggregateId", userId.toString());
        envelope.put("occurredAt", "2026-06-08T12:00:00Z");
        envelope.put("version", 1);
        envelope.set("payload", payload);
        return new ConsumerRecord<>("parkio.moderation.action", 0, 0L,
                userId.toString(), objectMapper.writeValueAsString(envelope));
    }
}
