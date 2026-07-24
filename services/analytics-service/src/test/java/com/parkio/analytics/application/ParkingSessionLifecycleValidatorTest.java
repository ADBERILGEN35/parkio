package com.parkio.analytics.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkio.analytics.application.event.ParkingSessionCancelledEvent;
import com.parkio.analytics.application.event.ParkingSessionCompletedEvent;
import com.parkio.analytics.application.event.ParkingSessionStartedEvent;
import com.parkio.analytics.domain.exception.AnalyticsContractException;
import com.parkio.platform.messaging.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParkingSessionLifecycleValidatorTest {

    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final Instant ENDED = Instant.parse("2026-07-24T12:30:00Z");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsValidStartedEnvelope() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        EventEnvelope envelope = envelope(eventId, "ParkingSessionStarted", sessionId, NOW);
        ParkingSessionStartedEvent event = new ParkingSessionStartedEvent(
                eventId, sessionId, UUID.randomUUID(), "ACTIVE", "MANUAL", NOW, NOW);
        assertThatCode(() -> ParkingSessionLifecycleValidator.validateStarted(envelope, event))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWrongAggregateType() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(
                eventId, "ParkingSessionStarted", "ParkingSpot", sessionId, NOW, 1, null, payloadNode());
        ParkingSessionStartedEvent event = new ParkingSessionStartedEvent(
                eventId, sessionId, UUID.randomUUID(), "ACTIVE", "MANUAL", NOW, NOW);
        assertThatThrownBy(() -> ParkingSessionLifecycleValidator.validateStarted(envelope, event))
                .isInstanceOf(AnalyticsContractException.class)
                .hasMessageContaining("aggregateType");
    }

    @Test
    void rejectsUnsupportedVersion() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(
                eventId, "ParkingSessionStarted", "ParkingSession", sessionId, NOW, 2, null, payloadNode());
        ParkingSessionStartedEvent event = new ParkingSessionStartedEvent(
                eventId, sessionId, UUID.randomUUID(), "ACTIVE", "MANUAL", NOW, NOW);
        assertThatThrownBy(() -> ParkingSessionLifecycleValidator.validateStarted(envelope, event))
                .isInstanceOf(AnalyticsContractException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsSessionIdMismatch() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        EventEnvelope envelope = envelope(eventId, "ParkingSessionStarted", sessionId, NOW);
        ParkingSessionStartedEvent event = new ParkingSessionStartedEvent(
                eventId, UUID.randomUUID(), UUID.randomUUID(), "ACTIVE", "MANUAL", NOW, NOW);
        assertThatThrownBy(() -> ParkingSessionLifecycleValidator.validateStarted(envelope, event))
                .isInstanceOf(AnalyticsContractException.class)
                .hasMessageContaining("sessionId");
    }

    @Test
    void rejectsInvalidStartedStatus() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        EventEnvelope envelope = envelope(eventId, "ParkingSessionStarted", sessionId, NOW);
        ParkingSessionStartedEvent event = new ParkingSessionStartedEvent(
                eventId, sessionId, UUID.randomUUID(), "COMPLETED", "MANUAL", NOW, NOW);
        assertThatThrownBy(() -> ParkingSessionLifecycleValidator.validateStarted(envelope, event))
                .isInstanceOf(AnalyticsContractException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void rejectsCompletedWithoutEndedAt() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        EventEnvelope envelope = envelope(eventId, "ParkingSessionCompleted", sessionId, ENDED);
        ParkingSessionCompletedEvent event = new ParkingSessionCompletedEvent(
                eventId, sessionId, UUID.randomUUID(), "COMPLETED", "MANUAL", NOW, null, ENDED);
        assertThatThrownBy(() -> ParkingSessionLifecycleValidator.validateCompleted(envelope, event))
                .isInstanceOf(AnalyticsContractException.class)
                .hasMessageContaining("endedAt");
    }

    @Test
    void rejectsEndedBeforeStarted() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        EventEnvelope envelope = envelope(eventId, "ParkingSessionCancelled", sessionId, NOW);
        ParkingSessionCancelledEvent event = new ParkingSessionCancelledEvent(
                eventId, sessionId, UUID.randomUUID(), "CANCELLED", "MANUAL", ENDED, NOW, NOW);
        assertThatThrownBy(() -> ParkingSessionLifecycleValidator.validateCancelled(envelope, event))
                .isInstanceOf(AnalyticsContractException.class)
                .hasMessageContaining("endedAt");
    }

    private EventEnvelope envelope(UUID eventId, String type, UUID sessionId, Instant occurredAt) {
        return new EventEnvelope(
                eventId, type, "ParkingSession", sessionId, occurredAt, 1, "trace", payloadNode());
    }

    private ObjectNode payloadNode() {
        return mapper.createObjectNode();
    }
}