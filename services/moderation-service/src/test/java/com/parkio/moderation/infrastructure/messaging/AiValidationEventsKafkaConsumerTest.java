package com.parkio.moderation.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.moderation.application.ModerationApplicationService;
import com.parkio.moderation.application.event.AiValidationCompletedEvent;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

/** Unit tests for the ai-validation→moderation consumer: dispatch, ignore, ack. */
class AiValidationEventsKafkaConsumerTest {

    // Mirrors the Spring Boot ObjectMapper the consumer is injected with (event-contracts.md).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ModerationApplicationService service = mock(ModerationApplicationService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final AiValidationEventsKafkaConsumer consumer =
            new AiValidationEventsKafkaConsumer(service, objectMapper);

    @Test
    void dispatchesAiValidationCompletedToHandlerAndAcks() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("mediaId", mediaId.toString());
        payload.putNull("parkingSpotId");
        payload.put("status", "FAILED");
        payload.put("emptySpaceConfidence", 0);
        payload.put("legalRiskScore", 10);
        payload.put("imageQualityScore", 20);
        payload.put("aiConfidence", 40);
        payload.putArray("detectedRiskTypes").add("NOT_A_PARKING_SPOT");
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record(eventId, mediaId, "AiValidationCompleted", payload),
                "AiValidationCompleted", ack);

        ArgumentCaptor<AiValidationCompletedEvent> captor = ArgumentCaptor.forClass(AiValidationCompletedEvent.class);
        verify(service).handleAiValidationCompleted(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo(eventId);
        assertThat(captor.getValue().status()).isEqualTo("FAILED");
        assertThat(captor.getValue().riskTypesOrEmpty()).contains("NOT_A_PARKING_SPOT");
        verify(ack).acknowledge();
    }

    @Test
    void ignoresUnknownEventTypeButStillAcks() throws Exception {
        UUID mediaId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("mediaId", mediaId.toString());
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record(UUID.randomUUID(), mediaId, "SomethingElse", payload), "SomethingElse", ack);

        verify(service, never()).handleAiValidationCompleted(any());
        verify(ack).acknowledge();
    }

    private ConsumerRecord<String, String> record(UUID eventId, UUID mediaId, String eventType, ObjectNode payload)
            throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", "AiValidationResult");
        envelope.put("aggregateId", mediaId.toString());
        envelope.put("occurredAt", "2026-06-08T12:00:00Z");
        envelope.put("version", 1);
        envelope.set("payload", payload);
        return new ConsumerRecord<>("parkio.aivalidation.result", 0, 0L,
                mediaId.toString(), objectMapper.writeValueAsString(envelope));
    }
}
