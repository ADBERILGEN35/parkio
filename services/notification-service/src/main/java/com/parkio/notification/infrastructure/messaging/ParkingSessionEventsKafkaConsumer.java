package com.parkio.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.notification.application.NotificationApplicationService;
import com.parkio.notification.application.event.ParkingSessionReminderRequestedEvent;
import com.parkio.platform.messaging.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code parkio.parking.session} for stale-session reminder requests.
 * Idempotency is enforced by the inbox inside the handler (dedupe by {@code eventId}).
 */
@Component
public class ParkingSessionEventsKafkaConsumer {

    public static final String PARKING_SESSION_TOPIC = "parkio.parking.session";
    public static final String GROUP = "parkio.notification";

    private static final String REMINDER_REQUESTED = "ParkingSessionReminderRequested";

    private static final Logger log = LoggerFactory.getLogger(ParkingSessionEventsKafkaConsumer.class);

    private final NotificationApplicationService notificationService;
    private final ObjectMapper objectMapper;

    public ParkingSessionEventsKafkaConsumer(
            NotificationApplicationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = PARKING_SESSION_TOPIC,
            groupId = GROUP,
            containerFactory = "gamificationScoreKafkaListenerContainerFactory")
    public void onMessage(
            ConsumerRecord<String, String> record,
            @Header(name = "eventType", required = false) String eventTypeHeader,
            Acknowledgment ack)
            throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();

        switch (eventType == null ? "" : eventType) {
            case REMINDER_REQUESTED -> notificationService.handleParkingSessionReminderRequested(
                    payload(envelope, ParkingSessionReminderRequestedEvent.class));
            default -> log.debug(
                    "Ignoring unsupported event type {} on {}", eventType, PARKING_SESSION_TOPIC);
        }
        ack.acknowledge();
    }

    private <T> T payload(EventEnvelope envelope, Class<T> type) throws Exception {
        return objectMapper.treeToValue(envelope.payload(), type);
    }
}
