package com.parkio.media.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

class EventEnvelopeCompatibilityTest {

    private final JsonMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void deserializesEnvelopeWithUnknownFutureFields() throws Exception {
        String json = """
                {
                  "eventId": "11111111-1111-1111-1111-111111111111",
                  "eventType": "MediaUploaded",
                  "aggregateType": "Media",
                  "aggregateId": "22222222-2222-2222-2222-222222222222",
                  "occurredAt": "2026-06-25T10:15:30Z",
                  "version": 1,
                  "traceId": "trace-123",
                  "payload": {"mediaId": "22222222-2222-2222-2222-222222222222"},
                  "futureField": {"ignored": true}
                }
                """;

        EventEnvelope envelope = objectMapper.readValue(json, EventEnvelope.class);

        assertThat(envelope.eventType()).isEqualTo("MediaUploaded");
        assertThat(envelope.payload().path("mediaId").asText())
                .isEqualTo("22222222-2222-2222-2222-222222222222");
    }
}
