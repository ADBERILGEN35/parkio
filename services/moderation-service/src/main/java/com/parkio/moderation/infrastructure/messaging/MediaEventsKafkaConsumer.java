package com.parkio.moderation.infrastructure.messaging;

import com.parkio.platform.messaging.EventEnvelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.moderation.application.ModerationApplicationService;
import com.parkio.moderation.application.event.MediaRejectedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code parkio.media.media} (group {@code parkio.moderation}) and opens a
 * moderation case for content-safety/relevance media rejections. Idempotency is enforced
 * by the inbox inside the handler (dedupe by {@code eventId}); the offset is acknowledged
 * only after the handler's transaction commits.
 *
 * <p>Only {@code MediaRejected} is handled; {@code MediaUploaded} and unknown future types
 * are ignored and acked. On failure the record is retried and ultimately dead-lettered by
 * the container's error handler (see {@link ModerationKafkaConsumerConfig}).
 */
@Component
public class MediaEventsKafkaConsumer {

    public static final String MEDIA_TOPIC = "parkio.media.media";
    public static final String GROUP = "parkio.moderation";

    private static final String MEDIA_REJECTED = "MediaRejected";

    private static final Logger log = LoggerFactory.getLogger(MediaEventsKafkaConsumer.class);

    private final ModerationApplicationService moderationService;
    private final ObjectMapper objectMapper;

    public MediaEventsKafkaConsumer(ModerationApplicationService moderationService, ObjectMapper objectMapper) {
        this.moderationService = moderationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = MEDIA_TOPIC,
            groupId = GROUP,
            containerFactory = "moderationKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();

        if (MEDIA_REJECTED.equals(eventType)) {
            moderationService.handleMediaRejected(
                    objectMapper.treeToValue(envelope.payload(), MediaRejectedEvent.class));
        } else {
            log.debug("Ignoring unsupported event type {} on {}", eventType, MEDIA_TOPIC);
        }
        ack.acknowledge();
    }
}
