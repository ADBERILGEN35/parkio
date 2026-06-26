package com.parkio.user.infrastructure.messaging;

import com.parkio.platform.messaging.EventEnvelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.user.application.UserApplicationService;
import com.parkio.user.application.event.UserRegisteredEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code parkio.auth.user} (group {@code parkio.user}) and provisions a user
 * profile from auth-service's {@code UserRegistered} event. Idempotency is enforced by
 * the inbox inside {@link UserApplicationService#handleUserRegistered} (dedupe by
 * {@code eventId}); the offset is acknowledged only after the handler's transaction
 * commits. Unknown event types are ignored and acked. On failure the record is retried
 * and ultimately dead-lettered by the container's error handler (see
 * {@link UserKafkaConsumerConfig}).
 */
@Component
public class UserRegisteredKafkaConsumer {

    public static final String AUTH_USER_TOPIC = "parkio.auth.user";
    public static final String GROUP = "parkio.user";

    private static final Logger log = LoggerFactory.getLogger(UserRegisteredKafkaConsumer.class);

    private final UserApplicationService userService;
    private final ObjectMapper objectMapper;

    public UserRegisteredKafkaConsumer(UserApplicationService userService, ObjectMapper objectMapper) {
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = AUTH_USER_TOPIC,
            groupId = GROUP,
            containerFactory = "authUserKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();

        if (!UserRegisteredEvent.TYPE.equals(eventType)) {
            // Not a type this consumer handles — safe to skip and ack.
            log.debug("Ignoring event type {} on {}", eventType, AUTH_USER_TOPIC);
            ack.acknowledge();
            return;
        }

        UserRegisteredEvent event = objectMapper.treeToValue(envelope.payload(), UserRegisteredEvent.class);
        userService.handleUserRegistered(event);
        ack.acknowledge();
    }
}
