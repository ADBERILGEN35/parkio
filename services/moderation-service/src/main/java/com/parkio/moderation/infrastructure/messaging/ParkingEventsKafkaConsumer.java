package com.parkio.moderation.infrastructure.messaging;

import com.parkio.platform.messaging.EventEnvelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.moderation.application.ModerationApplicationService;
import com.parkio.moderation.application.event.ParkingSpotRejectedEvent;
import com.parkio.moderation.application.event.ParkingSpotVerifiedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code parkio.parking.spot} (group {@code parkio.moderation}) and opens a
 * moderation case for illegal/risky community signals. Idempotency is enforced by the inbox
 * inside the handler (dedupe by {@code eventId}); the offset is acknowledged only after
 * the handler's transaction commits.
 *
 * <p>{@code ParkingSpotVerified} is handled only when its result is
 * {@code ILLEGAL_OR_RISKY}. Legacy {@code ParkingSpotRejected} events remain supported.
 * Other lifecycle events and unknown future types are ignored and acked. On failure the record is retried and
 * ultimately dead-lettered by the container's error handler (reuses the service's
 * {@code moderationKafkaListenerContainerFactory} → {@code parkio.dlt.moderation}).
 */
@Component
public class ParkingEventsKafkaConsumer {

    public static final String PARKING_SPOT_TOPIC = "parkio.parking.spot";
    public static final String GROUP = "parkio.moderation";

    private static final String SPOT_REJECTED = "ParkingSpotRejected";
    private static final String SPOT_VERIFIED = "ParkingSpotVerified";

    private static final Logger log = LoggerFactory.getLogger(ParkingEventsKafkaConsumer.class);

    private final ModerationApplicationService moderationService;
    private final ObjectMapper objectMapper;

    public ParkingEventsKafkaConsumer(ModerationApplicationService moderationService,
                                      ObjectMapper objectMapper) {
        this.moderationService = moderationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = PARKING_SPOT_TOPIC,
            groupId = GROUP,
            containerFactory = "moderationKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();

        switch (eventType == null ? "" : eventType) {
            case SPOT_VERIFIED -> moderationService.handleParkingSpotVerified(
                    objectMapper.treeToValue(envelope.payload(), ParkingSpotVerifiedEvent.class));
            case SPOT_REJECTED -> moderationService.handleParkingSpotRejected(
                    objectMapper.treeToValue(envelope.payload(), ParkingSpotRejectedEvent.class));
            default -> log.debug("Ignoring unsupported event type {} on {}", eventType, PARKING_SPOT_TOPIC);
        }
        ack.acknowledge();
    }
}
