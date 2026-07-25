package com.parkio.notification.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.notification.application.NotificationApplicationService;
import com.parkio.notification.application.event.ParkingSessionReminderRequestedEvent;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

class ParkingSessionEventsKafkaConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final NotificationApplicationService service = mock(NotificationApplicationService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final ParkingSessionEventsKafkaConsumer consumer =
            new ParkingSessionEventsKafkaConsumer(service, objectMapper);

    @Test
    void dispatchesReminderRequestedAndAcks() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("sessionId", sessionId.toString());
        payload.put("userId", userId.toString());
        payload.put("stage", "FIRST");
        payload.put("startedAt", "2026-07-20T10:00:00Z");
        payload.put("lastConfirmedAt", "2026-07-20T10:00:00Z");
        payload.put("occurredAt", "2026-07-21T10:00:00Z");

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "ParkingSessionReminderRequested");
        envelope.put("aggregateType", "ParkingSession");
        envelope.put("aggregateId", sessionId.toString());
        envelope.put("occurredAt", "2026-07-21T10:00:00Z");
        envelope.set("payload", payload);

        consumer.onMessage(
                new ConsumerRecord<>("parkio.parking.session", 0, 0L, sessionId.toString(),
                        objectMapper.writeValueAsString(envelope)),
                "ParkingSessionReminderRequested",
                ack);

        ArgumentCaptor<ParkingSessionReminderRequestedEvent> captor =
                ArgumentCaptor.forClass(ParkingSessionReminderRequestedEvent.class);
        verify(service).handleParkingSessionReminderRequested(captor.capture());
        verify(ack).acknowledge();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().stage()).isEqualTo("FIRST");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().sessionId()).isEqualTo(sessionId);
    }

    @Test
    void ignoresUnknownSessionEventsAndAcks() throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "ParkingSessionStarted");
        envelope.put("aggregateType", "ParkingSession");
        envelope.put("aggregateId", UUID.randomUUID().toString());
        envelope.put("occurredAt", "2026-07-21T10:00:00Z");
        envelope.set("payload", objectMapper.createObjectNode());

        consumer.onMessage(
                new ConsumerRecord<>("parkio.parking.session", 0, 0L, "k",
                        objectMapper.writeValueAsString(envelope)),
                "ParkingSessionStarted",
                ack);

        verify(service, org.mockito.Mockito.never())
                .handleParkingSessionReminderRequested(any());
        verify(ack).acknowledge();
    }
}
