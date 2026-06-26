package com.parkio.notification.infrastructure.messaging;

import com.parkio.platform.messaging.EventEnvelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.notification.application.NotificationApplicationService;
import com.parkio.notification.application.event.ParkingSpotCreatedEvent;
import com.parkio.notification.application.event.ParkingSpotRejectedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code parkio.parking.spot} (group {@code parkio.notification}) and notifies
 * the spot owner on creation/rejection. Idempotency is enforced by the inbox inside each
 * handler (dedupe by {@code eventId}); the offset is acknowledged only after the handler's
 * transaction commits.
 *
 * <p>Only {@code ParkingSpotCreated} and {@code ParkingSpotRejected} are handled; all
 * other parking-spot lifecycle events and unknown future types are ignored and acked. On
 * failure the record is retried and ultimately dead-lettered by the container's error
 * handler (reuses the service's {@code gamificationScoreKafkaListenerContainerFactory},
 * which dead-letters to {@code parkio.dlt.notification}).
 */
@Component
public class ParkingEventsKafkaConsumer {

    public static final String PARKING_SPOT_TOPIC = "parkio.parking.spot";
    public static final String GROUP = "parkio.notification";

    private static final String SPOT_CREATED = "ParkingSpotCreated";
    private static final String SPOT_REJECTED = "ParkingSpotRejected";

    private static final Logger log = LoggerFactory.getLogger(ParkingEventsKafkaConsumer.class);

    private final NotificationApplicationService notificationService;
    private final ObjectMapper objectMapper;

    public ParkingEventsKafkaConsumer(NotificationApplicationService notificationService,
                                      ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = PARKING_SPOT_TOPIC,
            groupId = GROUP,
            containerFactory = "gamificationScoreKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();

        switch (eventType == null ? "" : eventType) {
            case SPOT_CREATED -> notificationService.handleParkingSpotCreated(
                    payload(envelope, ParkingSpotCreatedEvent.class));
            case SPOT_REJECTED -> notificationService.handleParkingSpotRejected(
                    payload(envelope, ParkingSpotRejectedEvent.class));
            default -> log.debug("Ignoring unsupported event type {} on {}", eventType, PARKING_SPOT_TOPIC);
        }
        ack.acknowledge();
    }

    private <T> T payload(EventEnvelope envelope, Class<T> type) throws Exception {
        return objectMapper.treeToValue(envelope.payload(), type);
    }
}
