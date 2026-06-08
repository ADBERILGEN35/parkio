package com.parkio.gamification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.gamification.application.GamificationApplicationService;
import com.parkio.gamification.application.event.ParkingSpotCreatedEvent;
import com.parkio.gamification.application.event.ParkingSpotVerifiedEvent;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

/** Unit tests for the parking→gamification consumer: dispatch, ignore, ack. */
class ParkingEventsKafkaConsumerTest {

    // Mirrors the Spring Boot ObjectMapper the consumer is injected with (event-contracts.md).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final GamificationApplicationService service = mock(GamificationApplicationService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final ParkingEventsKafkaConsumer consumer = new ParkingEventsKafkaConsumer(service, objectMapper);

    @Test
    void dispatchesParkingSpotCreatedToHandlerAndAcks() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID spotId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("parkingSpotId", spotId.toString());
        payload.put("ownerUserId", ownerId.toString());
        // Extra producer-only fields must be tolerated (FAIL_ON_UNKNOWN_PROPERTIES=false).
        payload.put("mediaId", UUID.randomUUID().toString());
        payload.put("latitude", 41.0);
        payload.put("longitude", 29.0);
        payload.put("status", "ACTIVE");
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record(eventId, spotId, "ParkingSpotCreated", payload), "ParkingSpotCreated", ack);

        ArgumentCaptor<ParkingSpotCreatedEvent> captor = ArgumentCaptor.forClass(ParkingSpotCreatedEvent.class);
        verify(service).handleParkingSpotCreated(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo(eventId);
        assertThat(captor.getValue().parkingSpotId()).isEqualTo(spotId);
        assertThat(captor.getValue().ownerUserId()).isEqualTo(ownerId);
        verify(ack).acknowledge();
    }

    @Test
    void dispatchesParkingSpotVerifiedToHandlerAndAcks() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID spotId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("parkingSpotId", spotId.toString());
        payload.put("ownerUserId", UUID.randomUUID().toString());
        payload.put("actorUserId", UUID.randomUUID().toString());
        payload.put("result", "AVAILABLE");
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record(eventId, spotId, "ParkingSpotVerified", payload), "ParkingSpotVerified", ack);

        ArgumentCaptor<ParkingSpotVerifiedEvent> captor = ArgumentCaptor.forClass(ParkingSpotVerifiedEvent.class);
        verify(service).handleParkingSpotVerified(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo(eventId);
        assertThat(captor.getValue().isAvailable()).isTrue();
        verify(ack).acknowledge();
    }

    @Test
    void ignoresUnsupportedEventTypeButStillAcks() throws Exception {
        UUID spotId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("parkingSpotId", spotId.toString());
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record(UUID.randomUUID(), spotId, "ParkingSpotMarkedFilled", payload),
                "ParkingSpotMarkedFilled", ack);

        verify(service, never()).handleParkingSpotCreated(any());
        verify(service, never()).handleParkingSpotVerified(any());
        verify(service, never()).handleParkingSpotClaimed(any());
        verify(service, never()).handleParkingSpotRejected(any());
        verify(ack).acknowledge();
    }

    @Test
    void redeliveredEventIsDelegatedToIdempotentHandlerAndAcked() throws Exception {
        // The consumer always delegates; inbox dedup is enforced inside the handler
        // (covered by GamificationApplicationServiceTest.duplicateEventIsSkipped). Both
        // deliveries are acked so the partition advances.
        UUID eventId = UUID.randomUUID();
        UUID spotId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("parkingSpotId", spotId.toString());
        payload.put("ownerUserId", UUID.randomUUID().toString());
        payload.put("occurredAt", "2026-06-08T12:00:00Z");
        ConsumerRecord<String, String> rec = record(eventId, spotId, "ParkingSpotCreated", payload);

        consumer.onMessage(rec, "ParkingSpotCreated", ack);
        consumer.onMessage(rec, "ParkingSpotCreated", ack); // redelivery

        verify(service, times(2)).handleParkingSpotCreated(any());
        verify(ack, times(2)).acknowledge();
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
