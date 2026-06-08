package com.parkio.gamification.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gamification.application.GamificationApplicationService;
import com.parkio.gamification.application.event.ParkingSpotRejectedByModeratorEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code parkio.moderation.action} (group {@code parkio.gamification}) and
 * applies the owner penalty for a moderator-driven spot rejection. Idempotency is
 * enforced by the inbox inside the handler (dedupe by {@code eventId}); the offset is
 * acknowledged only after the handler's transaction commits.
 *
 * <p>Only {@code ParkingSpotRejectedByModerator} is handled; {@code UserSuspended},
 * {@code UserRestored} and unknown future types are ignored and acked. On failure the
 * record is retried and ultimately dead-lettered by the container's error handler
 * (reuses the service's {@code parkingEventsKafkaListenerContainerFactory} →
 * {@code parkio.dlt.gamification}).
 */
@Component
public class ModerationActionsKafkaConsumer {

    public static final String MODERATION_ACTION_TOPIC = "parkio.moderation.action";
    public static final String GROUP = "parkio.gamification";

    private static final String SPOT_REJECTED_BY_MODERATOR = "ParkingSpotRejectedByModerator";

    private static final Logger log = LoggerFactory.getLogger(ModerationActionsKafkaConsumer.class);

    private final GamificationApplicationService gamificationService;
    private final ObjectMapper objectMapper;

    public ModerationActionsKafkaConsumer(GamificationApplicationService gamificationService,
                                          ObjectMapper objectMapper) {
        this.gamificationService = gamificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = MODERATION_ACTION_TOPIC,
            groupId = GROUP,
            containerFactory = "parkingEventsKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();

        if (SPOT_REJECTED_BY_MODERATOR.equals(eventType)) {
            gamificationService.handleParkingSpotRejectedByModerator(
                    objectMapper.treeToValue(envelope.payload(), ParkingSpotRejectedByModeratorEvent.class));
        } else {
            log.debug("Ignoring unsupported event type {} on {}", eventType, MODERATION_ACTION_TOPIC);
        }
        ack.acknowledge();
    }
}
