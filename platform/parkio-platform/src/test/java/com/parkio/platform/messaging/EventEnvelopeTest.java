package com.parkio.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void preservesEnvelopeWireShapeAndIgnoresUnknownFields() throws Exception {
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID aggregateId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "ParkingSpotCreated",
                  "aggregateType": "parkingSpot",
                  "aggregateId": "%s",
                  "occurredAt": "2026-06-26T10:15:30Z",
                  "version": 1,
                  "traceId": "corr-123",
                  "payload": {"spotId": "%s"},
                  "futureField": "ignored"
                }
                """.formatted(eventId, aggregateId, aggregateId);

        EventEnvelope envelope = objectMapper.readValue(json, EventEnvelope.class);

        assertThat(envelope.eventId()).isEqualTo(eventId);
        assertThat(envelope.eventType()).isEqualTo("ParkingSpotCreated");
        assertThat(envelope.aggregateType()).isEqualTo("parkingSpot");
        assertThat(envelope.aggregateId()).isEqualTo(aggregateId);
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-06-26T10:15:30Z"));
        assertThat(envelope.version()).isEqualTo(EventEnvelope.CURRENT_VERSION);
        assertThat(envelope.traceId()).isEqualTo("corr-123");
        assertThat(envelope.payload().get("spotId").asText()).isEqualTo(aggregateId.toString());
    }

    @Test
    void writesSameTopLevelNames() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"name\":\"Central\"}");
        EventEnvelope envelope = new EventEnvelope(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "MediaUploaded",
                "media",
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                Instant.parse("2026-06-26T10:15:30Z"),
                1,
                "corr-123",
                payload);

        String json = objectMapper.writeValueAsString(envelope);

        assertThat(json).contains("\"eventId\"");
        assertThat(json).contains("\"eventType\"");
        assertThat(json).contains("\"aggregateType\"");
        assertThat(json).contains("\"aggregateId\"");
        assertThat(json).contains("\"occurredAt\"");
        assertThat(json).contains("\"version\"");
        assertThat(json).contains("\"traceId\"");
        assertThat(json).contains("\"payload\"");
    }
}
