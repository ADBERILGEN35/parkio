package com.parkio.notification.infrastructure.messaging;

import com.parkio.platform.messaging.EventEnvelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.notification.application.NotificationApplicationService;
import com.parkio.notification.application.event.PointsDeductedEvent;
import com.parkio.notification.application.event.PointsEarnedEvent;
import com.parkio.notification.application.event.UserLevelChangedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code parkio.gamification.score} (group {@code parkio.notification}) and
 * dispatches by {@code eventType} to the matching notification handler. Idempotency is
 * enforced by the inbox inside each handler (dedupe by {@code eventId}); the offset is
 * acknowledged only after the handler's transaction commits.
 *
 * <p>Unsupported types ({@code ContributionScoreUpdated}) and unknown future types are
 * ignored and acked. On failure the record is retried and ultimately dead-lettered by the
 * container's error handler (see {@link NotificationKafkaConsumerConfig}).
 */
@Component
public class GamificationScoreKafkaConsumer {

    public static final String GAMIFICATION_SCORE_TOPIC = "parkio.gamification.score";
    public static final String GROUP = "parkio.notification";

    private static final String POINTS_EARNED = "PointsEarned";
    private static final String POINTS_DEDUCTED = "PointsDeducted";
    private static final String USER_LEVEL_CHANGED = "UserLevelChanged";

    private static final Logger log = LoggerFactory.getLogger(GamificationScoreKafkaConsumer.class);

    private final NotificationApplicationService notificationService;
    private final ObjectMapper objectMapper;

    public GamificationScoreKafkaConsumer(NotificationApplicationService notificationService,
                                          ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = GAMIFICATION_SCORE_TOPIC,
            groupId = GROUP,
            containerFactory = "gamificationScoreKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();

        switch (eventType == null ? "" : eventType) {
            case POINTS_EARNED -> notificationService.handlePointsEarned(
                    payload(envelope, PointsEarnedEvent.class));
            case POINTS_DEDUCTED -> notificationService.handlePointsDeducted(
                    payload(envelope, PointsDeductedEvent.class));
            case USER_LEVEL_CHANGED -> notificationService.handleUserLevelChanged(
                    payload(envelope, UserLevelChangedEvent.class));
            default -> log.debug("Ignoring unsupported event type {} on {}", eventType, GAMIFICATION_SCORE_TOPIC);
        }
        ack.acknowledge();
    }

    private <T> T payload(EventEnvelope envelope, Class<T> type) throws Exception {
        return objectMapper.treeToValue(envelope.payload(), type);
    }
}
