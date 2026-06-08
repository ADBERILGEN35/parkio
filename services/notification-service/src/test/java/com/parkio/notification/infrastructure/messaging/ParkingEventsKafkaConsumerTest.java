package com.parkio.notification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.notification.application.NotificationApplicationService;
import com.parkio.notification.application.event.ParkingSpotCreatedEvent;
import com.parkio.notification.application.event.ParkingSpotRejectedEvent;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

/** Unit tests for the parking→notification consumer: dispatch, ignore, ack. */
class ParkingEventsKafkaConsumerTest {

    // Mirrors the Spring Boot ObjectMapper the consumer is injected with (event-contracts.md).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final NotificationApplicationService service = mock(NotificationApplicationService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final ParkingEventsKafkaConsumer consumer = new ParkingEventsKafkaConsumer(service, objectMapper);

    @Test
    void dispatchesParkingSpotCreatedAndAcks() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID spotId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("parkingSpotId", spotId.toString());
        payload.put("ownerUserId", UUID.randomUUID().toString());
        // Producer-only fields must be tolerated.
        payload.put("mediaId", UUID.randomUUID().toString());
        payload.put("status", "ACTIVE");
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record(eventId, spotId, "ParkingSpotCreated", payload), "ParkingSpotCreated", ack);

        ArgumentCaptor<ParkingSpotCreatedEvent> captor = ArgumentCaptor.forClass(ParkingSpotCreatedEvent.class);
        verify(service).handleParkingSpotCreated(captor.capture());
        assertThat(captor.getValue().parkingSpotId()).isEqualTo(spotId);
        verify(ack).acknowledge();
    }

    @Test
    void dispatchesParkingSpotRejectedAndAcks() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID spotId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("parkingSpotId", spotId.toString());
        payload.put("ownerUserId", UUID.randomUUID().toString());
        payload.put("actorUserId", UUID.randomUUID().toString());
        payload.put("result", "ILLEGAL_OR_RISKY");
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record(eventId, spotId, "ParkingSpotRejected", payload), "ParkingSpotRejected", ack);

        ArgumentCaptor<ParkingSpotRejectedEvent> captor = ArgumentCaptor.forClass(ParkingSpotRejectedEvent.class);
        verify(service).handleParkingSpotRejected(captor.capture());
        assertThat(captor.getValue().parkingSpotId()).isEqualTo(spotId);
        verify(ack).acknowledge();
    }

    @Test
    void ignoresUnsupportedEventTypeButStillAcks() throws Exception {
        UUID spotId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("parkingSpotId", spotId.toString());
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record(UUID.randomUUID(), spotId, "ParkingSpotVerified", payload),
                "ParkingSpotVerified", ack);

        verify(service, never()).handleParkingSpotCreated(any());
        verify(service, never()).handleParkingSpotRejected(any());
        verify(ack).acknowledge();
    }

    private ConsumerRecord<String, String> record(UUID eventId, UUID spotId, String eventType, ObjectNode payload)
            throws Exception {
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
