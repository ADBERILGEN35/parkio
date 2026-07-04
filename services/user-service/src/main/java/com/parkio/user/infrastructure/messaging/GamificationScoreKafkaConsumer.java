package com.parkio.user.infrastructure.messaging;

import com.parkio.platform.messaging.EventEnvelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.user.application.UserApplicationService;
import com.parkio.user.application.event.PointsDeductedEvent;
import com.parkio.user.application.event.PointsEarnedEvent;
import com.parkio.user.application.event.TrustScoreUpdatedEvent;
import com.parkio.user.application.event.UserLevelChangedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code parkio.gamification.score} (group {@code parkio.user}) and maintains the
 * trust/gamification projection ({@code user_trust_profiles}) behind {@code /me/stats} and
 * the public profile. Idempotency is enforced by the inbox inside each handler (dedupe by
 * {@code eventId}); the offset is acknowledged only after the handler's transaction commits.
 *
 * <p>{@code ContributionScoreUpdated} and unknown future types are ignored and acked. On
 * failure the record is retried and ultimately dead-lettered by the container's error
 * handler (reuses the service's {@code authUserKafkaListenerContainerFactory} →
 * {@code parkio.dlt.user}).
 */
@Component
public class GamificationScoreKafkaConsumer {

    public static final String GAMIFICATION_SCORE_TOPIC = "parkio.gamification.score";
    public static final String GROUP = "parkio.user";

    private static final Logger log = LoggerFactory.getLogger(GamificationScoreKafkaConsumer.class);

    private final UserApplicationService userService;
    private final ObjectMapper objectMapper;

    public GamificationScoreKafkaConsumer(UserApplicationService userService, ObjectMapper objectMapper) {
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = GAMIFICATION_SCORE_TOPIC,
            groupId = GROUP,
            containerFactory = "authUserKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();

        switch (eventType == null ? "" : eventType) {
            case PointsEarnedEvent.TYPE -> userService.handlePointsEarned(
                    objectMapper.treeToValue(envelope.payload(), PointsEarnedEvent.class));
            case PointsDeductedEvent.TYPE -> userService.handlePointsDeducted(
                    objectMapper.treeToValue(envelope.payload(), PointsDeductedEvent.class));
            case UserLevelChangedEvent.TYPE -> userService.handleUserLevelChanged(
                    objectMapper.treeToValue(envelope.payload(), UserLevelChangedEvent.class));
            case TrustScoreUpdatedEvent.TYPE -> userService.handleTrustScoreUpdated(
                    objectMapper.treeToValue(envelope.payload(), TrustScoreUpdatedEvent.class));
            default -> log.debug("Ignoring unsupported event type {} on {}", eventType, GAMIFICATION_SCORE_TOPIC);
        }
        ack.acknowledge();
    }
}
