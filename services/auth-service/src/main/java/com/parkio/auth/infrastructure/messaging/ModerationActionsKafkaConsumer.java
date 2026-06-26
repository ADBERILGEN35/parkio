package com.parkio.auth.infrastructure.messaging;

import com.parkio.platform.messaging.EventEnvelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.event.UserRestoredEvent;
import com.parkio.auth.application.event.UserSuspendedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code parkio.moderation.action} (group {@code parkio.auth}) and applies the
 * account-status side of moderator decisions to the auth account: suspension blocks
 * login/refresh and revokes active refresh tokens; restoration re-enables login.
 * Idempotency is enforced by the inbox inside each handler (dedupe by {@code eventId});
 * the offset is acknowledged only after the handler's transaction commits.
 *
 * <p>Only {@code UserSuspended} / {@code UserRestored} are handled;
 * {@code ParkingSpotRejectedByModerator} and unknown future types are ignored and acked.
 * On failure the record is retried and ultimately dead-lettered by the container's error
 * handler ({@code authKafkaListenerContainerFactory} → {@code parkio.dlt.auth}).
 */
@Component
public class ModerationActionsKafkaConsumer {

    public static final String MODERATION_ACTION_TOPIC = "parkio.moderation.action";
    public static final String GROUP = "parkio.auth";

    private static final String USER_SUSPENDED = "UserSuspended";
    private static final String USER_RESTORED = "UserRestored";

    private static final Logger log = LoggerFactory.getLogger(ModerationActionsKafkaConsumer.class);

    private final AuthApplicationService authService;
    private final ObjectMapper objectMapper;

    public ModerationActionsKafkaConsumer(AuthApplicationService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = MODERATION_ACTION_TOPIC,
            groupId = GROUP,
            containerFactory = "authKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();

        switch (eventType == null ? "" : eventType) {
            case USER_SUSPENDED -> authService.handleUserSuspended(
                    objectMapper.treeToValue(envelope.payload(), UserSuspendedEvent.class));
            case USER_RESTORED -> authService.handleUserRestored(
                    objectMapper.treeToValue(envelope.payload(), UserRestoredEvent.class));
            default -> log.debug("Ignoring unsupported event type {} on {}", eventType, MODERATION_ACTION_TOPIC);
        }
        ack.acknowledge();
    }
}
