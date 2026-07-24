package com.parkio.analytics.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.analytics.application.AnalyticsApplicationService;
import com.parkio.analytics.domain.exception.AnalyticsContractException;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/** Unit tests for the parking-session→analytics consumer: dispatch, validation, ack. */
class ParkingSessionEventsKafkaConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final AnalyticsApplicationService service = mock(AnalyticsApplicationService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final ParkingSessionEventsKafkaConsumer consumer =
            new ParkingSessionEventsKafkaConsumer(service, objectMapper);

    @Test
    void dispatchesAllLifecycleTypesAndAcks() throws Exception {
        consumer.onMessage(startedRecord("MANUAL"), "ParkingSessionStarted", ack);
        consumer.onMessage(terminalRecord("ParkingSessionCompleted", "COMPLETED", "MANUAL"),
                "ParkingSessionCompleted", ack);
        consumer.onMessage(terminalRecord("ParkingSessionCancelled", "CANCELLED", "COMMUNITY"),
                "ParkingSessionCancelled", ack);

        verify(service).handleParkingSessionStarted(any());
        verify(service).handleParkingSessionCompleted(any());
        verify(service).handleParkingSessionCancelled(any());
        verify(ack, org.mockito.Mockito.times(3)).acknowledge();
    }

    @Test
    void communityStartedDoesNotInvokeSpotClaimHandler() throws Exception {
        consumer.onMessage(startedRecord("COMMUNITY"), "ParkingSessionStarted", ack);

        verify(service).handleParkingSessionStarted(any());
        verify(service, never()).handleParkingSpotClaimed(any());
        verify(ack).acknowledge();
    }

    @Test
    void unsupportedEventTypeIsRejectedWithoutAck() {
        assertThatThrownBy(() ->
                        consumer.onMessage(startedRecord("MANUAL"), "ParkingSessionDeleted", ack))
                .isInstanceOf(AnalyticsContractException.class);
        verify(service, never()).handleParkingSessionStarted(any());
        verify(ack, never()).acknowledge();
    }

    @Test
    void unsupportedVersionIsRejectedWithoutAck() {
        assertThatThrownBy(() ->
                        consumer.onMessage(startedRecord("MANUAL", 2), "ParkingSessionStarted", ack))
                .isInstanceOf(AnalyticsContractException.class);
        verify(service, never()).handleParkingSessionStarted(any());
        verify(ack, never()).acknowledge();
    }

    @Test
    void wrongAggregateTypeIsRejectedWithoutAck() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ObjectNode payload = basePayload(eventId, sessionId, "ACTIVE", "MANUAL", false);
        ObjectNode envelope = envelope(eventId, "ParkingSessionStarted", "ParkingSpot", sessionId, 1, payload);

        assertThatThrownBy(() -> consumer.onMessage(
                        new ConsumerRecord<>("parkio.parking.session", 0, 0L,
                                sessionId.toString(), objectMapper.writeValueAsString(envelope)),
                        "ParkingSessionStarted",
                        ack))
                .isInstanceOf(AnalyticsContractException.class);
        verify(ack, never()).acknowledge();
    }

    @Test
    void producerOnlyFieldsAreTolerated() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ObjectNode payload = basePayload(eventId, sessionId, "ACTIVE", "MANUAL", false);
        // Extra producer fields must not break deserialization (ignoreUnknown).
        payload.put("futureAdditiveField", "ok");
        ObjectNode envelope = envelope(eventId, "ParkingSessionStarted", "ParkingSession", sessionId, 1, payload);

        consumer.onMessage(new ConsumerRecord<>("parkio.parking.session", 0, 0L,
                sessionId.toString(), objectMapper.writeValueAsString(envelope)),
                "ParkingSessionStarted", ack);

        verify(service).handleParkingSessionStarted(any());
        verify(ack).acknowledge();
    }

    private ConsumerRecord<String, String> startedRecord(String source) throws Exception {
        return startedRecord(source, 1);
    }

    private ConsumerRecord<String, String> startedRecord(String source, int version) throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ObjectNode payload = basePayload(eventId, sessionId, "ACTIVE", source, false);
        ObjectNode envelope = envelope(eventId, "ParkingSessionStarted", "ParkingSession", sessionId, version, payload);
        return new ConsumerRecord<>("parkio.parking.session", 0, 0L,
                sessionId.toString(), objectMapper.writeValueAsString(envelope));
    }

    private ConsumerRecord<String, String> terminalRecord(String eventType, String status, String source)
            throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ObjectNode payload = basePayload(eventId, sessionId, status, source, true);
        ObjectNode envelope = envelope(eventId, eventType, "ParkingSession", sessionId, 1, payload);
        return new ConsumerRecord<>("parkio.parking.session", 0, 0L,
                sessionId.toString(), objectMapper.writeValueAsString(envelope));
    }

    private ObjectNode basePayload(
            UUID eventId, UUID sessionId, String status, String source, boolean terminal) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("sessionId", sessionId.toString());
        payload.put("userId", UUID.randomUUID().toString());
        payload.put("status", status);
        payload.put("source", source);
        payload.put("startedAt", "2026-07-24T12:00:00Z");
        payload.put("occurredAt", "2026-07-24T12:00:00Z");
        if (terminal) {
            payload.put("endedAt", "2026-07-24T12:30:00Z");
            payload.put("occurredAt", "2026-07-24T12:30:00Z");
        }
        return payload;
    }

    private ObjectNode envelope(
            UUID eventId, String eventType, String aggregateType, UUID aggregateId, int version, ObjectNode payload) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("occurredAt", "2026-07-24T12:00:00Z");
        envelope.put("version", version);
        envelope.put("traceId", "trace-session");
        envelope.set("payload", payload);
        return envelope;
    }
}