package com.parkio.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.notification.application.NotificationApplicationService;
import com.parkio.notification.application.event.ParkingSpotRejectedByModeratorEvent;
import com.parkio.notification.application.event.UserRestoredEvent;
import com.parkio.notification.application.event.UserSuspendedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code parkio.moderation.action} (group {@code parkio.notification}) and
 * notifies users of suspension/restoration and spot owners of moderator rejections.
 * Idempotency is enforced by the inbox inside each handler (dedupe by {@code eventId});
 * the offset is acknowledged only after the handler's transaction commits.
 *
 * <p>Unknown future types are ignored and acked. On failure the record is retried and
 * ultimately dead-lettered (reuses {@code gamificationScoreKafkaListenerContainerFactory}
 * → {@code parkio.dlt.notification}).
 */
@Component
public class ModerationActionsKafkaConsumer {

    public static final String MODERATION_ACTION_TOPIC = "parkio.moderation.action";
    public static final String GROUP = "parkio.notification";

    private static final String USER_SUSPENDED = "UserSuspended";
    private static final String USER_RESTORED = "UserRestored";
    private static final String SPOT_REJECTED_BY_MODERATOR = "ParkingSpotRejectedByModerator";

    private static final Logger log = LoggerFactory.getLogger(ModerationActionsKafkaConsumer.class);

    private final NotificationApplicationService notificationService;
    private final ObjectMapper objectMapper;

    public ModerationActionsKafkaConsumer(NotificationApplicationService notificationService,
                                          ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = MODERATION_ACTION_TOPIC,
            groupId = GROUP,
            containerFactory = "gamificationScoreKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();

        switch (eventType == null ? "" : eventType) {
            case USER_SUSPENDED -> notificationService.handleUserSuspended(
                    objectMapper.treeToValue(envelope.payload(), UserSuspendedEvent.class));
            case USER_RESTORED -> notificationService.handleUserRestored(
                    objectMapper.treeToValue(envelope.payload(), UserRestoredEvent.class));
            case SPOT_REJECTED_BY_MODERATOR -> notificationService.handleParkingSpotRejectedByModerator(
                    objectMapper.treeToValue(envelope.payload(), ParkingSpotRejectedByModeratorEvent.class));
            default -> log.debug("Ignoring unsupported event type {} on {}", eventType, MODERATION_ACTION_TOPIC);
        }
        ack.acknowledge();
    }
}
