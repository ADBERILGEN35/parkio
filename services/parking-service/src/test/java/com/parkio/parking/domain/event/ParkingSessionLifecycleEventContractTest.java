package com.parkio.parking.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSource;
import java.time.Instant;
import java.util.Iterator;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Locks the privacy-minimized ParkingSession lifecycle event wire contract (v1). */
class ParkingSessionLifecycleEventContractTest {

    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final Instant ENDED = Instant.parse("2026-07-24T12:30:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void startedPayloadIsPrivacyMinimizedAndVersionStable() throws Exception {
        ParkingSession session = ParkingSession.start(
                UUID.randomUUID(), ParkingSource.MANUAL, 41.0082, 28.9784, null, null, NOW);
        ParkingSessionStartedEvent event = ParkingSessionStartedEvent.of(session, NOW);

        JsonNode json = objectMapper.valueToTree(event);

        assertThat(event.eventType()).isEqualTo("ParkingSessionStarted");
        assertThat(event.aggregateType()).isEqualTo("ParkingSession");
        assertThat(event.aggregateId()).isEqualTo(session.getId());
        assertThat(json.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(json.get("sessionId").asText()).isEqualTo(session.getId().toString());
        assertThat(json.get("userId").asText()).isEqualTo(session.getUserId().toString());
        assertThat(json.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(json.get("source").asText()).isEqualTo("MANUAL");
        assertThat(json.get("startedAt").asText()).isEqualTo("2026-07-24T12:00:00Z");
        assertThat(json.get("occurredAt").asText()).isEqualTo("2026-07-24T12:00:00Z");
        assertNoSensitiveFields(json);
    }

    @Test
    void completedPayloadIncludesTerminalTimestampsWithoutCoordinates() throws Exception {
        ParkingSession session = ParkingSession.start(
                UUID.randomUUID(), ParkingSource.COMMUNITY, 40.0, 29.0, null, null, NOW);
        session.complete(ENDED);
        ParkingSessionCompletedEvent event = ParkingSessionCompletedEvent.of(session, ENDED);

        JsonNode json = objectMapper.valueToTree(event);

        assertThat(event.eventType()).isEqualTo("ParkingSessionCompleted");
        assertThat(json.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(json.get("source").asText()).isEqualTo("COMMUNITY");
        assertThat(json.get("startedAt").asText()).isEqualTo("2026-07-24T12:00:00Z");
        assertThat(json.get("endedAt").asText()).isEqualTo("2026-07-24T12:30:00Z");
        assertNoSensitiveFields(json);
    }

    @Test
    void cancelledPayloadIncludesServerEndedAt() throws Exception {
        ParkingSession session = ParkingSession.start(
                UUID.randomUUID(), ParkingSource.AUTO, 40.0, 29.0, null, null, NOW);
        session.cancel(ENDED);
        ParkingSessionCancelledEvent event = ParkingSessionCancelledEvent.of(session, ENDED);

        JsonNode json = objectMapper.valueToTree(event);

        assertThat(event.eventType()).isEqualTo("ParkingSessionCancelled");
        assertThat(json.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(json.get("endedAt").asText()).isEqualTo("2026-07-24T12:30:00Z");
        assertThat(json.has("durationSeconds")).isFalse();
        assertNoSensitiveFields(json);
    }

    private static void assertNoSensitiveFields(JsonNode json) {
        Iterator<String> names = json.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            assertThat(name).isNotIn(
                    "latitude", "longitude", "location", "geohash", "address",
                    "estimatedFee", "reminderAt", "idempotencyKey", "Idempotency-Key",
                    "accessToken", "refreshToken", "email", "displayName", "spotId",
                    "parkingSpotId");
        }
        assertThat(json.has("latitude")).isFalse();
        assertThat(json.has("longitude")).isFalse();
        assertThat(json.toString()).doesNotContain("41.0082");
        assertThat(json.toString()).doesNotContain("28.9784");
    }
}