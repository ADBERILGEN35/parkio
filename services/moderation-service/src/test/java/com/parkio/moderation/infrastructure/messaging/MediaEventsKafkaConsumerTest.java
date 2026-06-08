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
import com.parkio.moderation.application.event.MediaRejectedEvent;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

/** Unit tests for the media→moderation consumer: dispatch, ignore, ack. */
class MediaEventsKafkaConsumerTest {

    // Mirrors the Spring Boot ObjectMapper the consumer is injected with (event-contracts.md).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ModerationApplicationService service = mock(ModerationApplicationService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final MediaEventsKafkaConsumer consumer = new MediaEventsKafkaConsumer(service, objectMapper);

    @Test
    void dispatchesMediaRejectedToHandlerAndAcks() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("mediaId", mediaId.toString());
        payload.put("ownerUserId", UUID.randomUUID().toString());
        payload.put("validationType", "IMAGE_SAFETY");
        payload.put("reason", "unsafe content");
        payload.put("checksum", "abc123");
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record(eventId, mediaId, "MediaRejected", payload), "MediaRejected", ack);

        ArgumentCaptor<MediaRejectedEvent> captor = ArgumentCaptor.forClass(MediaRejectedEvent.class);
        verify(service).handleMediaRejected(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo(eventId);
        assertThat(captor.getValue().validationType()).isEqualTo("IMAGE_SAFETY");
        verify(ack).acknowledge();
    }

    @Test
    void ignoresMediaUploadedButStillAcks() throws Exception {
        UUID mediaId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("mediaId", mediaId.toString());
        payload.put("occurredAt", "2026-06-08T12:00:00Z");

        consumer.onMessage(record(UUID.randomUUID(), mediaId, "MediaUploaded", payload), "MediaUploaded", ack);

        verify(service, never()).handleMediaRejected(any());
        verify(ack).acknowledge();
    }

    private ConsumerRecord<String, String> record(UUID eventId, UUID mediaId, String eventType, ObjectNode payload)
            throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", "Media");
        envelope.put("aggregateId", mediaId.toString());
        envelope.put("occurredAt", "2026-06-08T12:00:00Z");
        envelope.put("version", 1);
        envelope.set("payload", payload);
        return new ConsumerRecord<>("parkio.media.media", 0, 0L,
                mediaId.toString(), objectMapper.writeValueAsString(envelope));
    }
}
