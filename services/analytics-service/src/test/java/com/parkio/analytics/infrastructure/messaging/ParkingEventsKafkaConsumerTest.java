package com.parkio.analytics.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.analytics.application.AnalyticsApplicationService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/** Unit tests for the parking→analytics consumer: dispatch, ignore, ack. */
class ParkingEventsKafkaConsumerTest {

    // Mirrors the Spring Boot ObjectMapper the consumer is injected with (event-contracts.md).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final AnalyticsApplicationService service = mock(AnalyticsApplicationService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final ParkingEventsKafkaConsumer consumer = new ParkingEventsKafkaConsumer(service, objectMapper);

    @Test
    void dispatchesAllProjectedTypesAndAcks() throws Exception {
        consumer.onMessage(record("ParkingSpotCreated"), "ParkingSpotCreated", ack);
        consumer.onMessage(record("ParkingSpotVerified"), "ParkingSpotVerified", ack);
        consumer.onMessage(record("ParkingSpotClaimed"), "ParkingSpotClaimed", ack);
        consumer.onMessage(record("ParkingSpotRejected"), "ParkingSpotRejected", ack);

        verify(service).handleParkingSpotCreated(any());
        verify(service).handleParkingSpotVerified(any());
        verify(service).handleParkingSpotClaimed(any());
        verify(service).handleParkingSpotRejected(any());
        verify(ack, org.mockito.Mockito.times(4)).acknowledge();
    }

    @Test
    void ignoresUnsupportedEventTypeButStillAcks() throws Exception {
        consumer.onMessage(record("ParkingSpotMarkedFilled"), "ParkingSpotMarkedFilled", ack);

        verify(service, never()).handleParkingSpotCreated(any());
        verify(service, never()).handleParkingSpotVerified(any());
        verify(service, never()).handleParkingSpotClaimed(any());
        verify(service, never()).handleParkingSpotRejected(any());
        verify(ack).acknowledge();
    }

    private ConsumerRecord<String, String> record(String eventType) throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID spotId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("parkingSpotId", spotId.toString());
        payload.put("ownerUserId", UUID.randomUUID().toString());
        payload.put("actorUserId", UUID.randomUUID().toString());
        payload.put("result", "AVAILABLE");
        // Producer-only fields must be tolerated.
        payload.put("verificationCount", 2);
        payload.put("status", "VERIFIED");
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", "ParkingSpot");
        envelope.put("aggregateId", spotId.toString());
        envelope.put("occurredAt", "2026-06-08T12:00:00Z");
        envelope.put("version", 1);
        envelope.set("payload", payload);
        return new ConsumerRecord<>("parkio.parking.spot", 0, 0L,
                spotId.toString(), objectMapper.writeValueAsString(envelope));
    }
}
