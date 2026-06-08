package com.parkio.gamification.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gamification.application.GamificationApplicationService;
import com.parkio.gamification.application.event.ParkingSpotClaimedEvent;
import com.parkio.gamification.application.event.ParkingSpotCreatedEvent;
import com.parkio.gamification.application.event.ParkingSpotRejectedEvent;
import com.parkio.gamification.application.event.ParkingSpotVerifiedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code parkio.parking.spot} (group {@code parkio.gamification}) and dispatches
 * by {@code eventType} to the matching reward/penalty handler. Idempotency is enforced by
 * the inbox inside each handler (dedupe by {@code eventId}); the offset is acknowledged
 * only after the handler's transaction commits.
 *
 * <p>Unsupported types ({@code ParkingSpotMarkedFilled}, {@code ParkingSpotExpired}) and
 * unknown future types are ignored and acked. On failure the record is retried and
 * ultimately dead-lettered by the container's error handler (see
 * {@link GamificationKafkaConsumerConfig}).
 */
@Component
public class ParkingEventsKafkaConsumer {

    public static final String PARKING_SPOT_TOPIC = "parkio.parking.spot";
    public static final String GROUP = "parkio.gamification";

    private static final String SPOT_CREATED = "ParkingSpotCreated";
    private static final String SPOT_VERIFIED = "ParkingSpotVerified";
    private static final String SPOT_CLAIMED = "ParkingSpotClaimed";
    private static final String SPOT_REJECTED = "ParkingSpotRejected";

    private static final Logger log = LoggerFactory.getLogger(ParkingEventsKafkaConsumer.class);

    private final GamificationApplicationService gamificationService;
    private final ObjectMapper objectMapper;

    public ParkingEventsKafkaConsumer(GamificationApplicationService gamificationService,
                                      ObjectMapper objectMapper) {
        this.gamificationService = gamificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = PARKING_SPOT_TOPIC,
            groupId = GROUP,
            containerFactory = "parkingEventsKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();

        switch (eventType == null ? "" : eventType) {
            case SPOT_CREATED -> gamificationService.handleParkingSpotCreated(
                    payload(envelope, ParkingSpotCreatedEvent.class));
            case SPOT_VERIFIED -> gamificationService.handleParkingSpotVerified(
                    payload(envelope, ParkingSpotVerifiedEvent.class));
            case SPOT_CLAIMED -> gamificationService.handleParkingSpotClaimed(
                    payload(envelope, ParkingSpotClaimedEvent.class));
            case SPOT_REJECTED -> gamificationService.handleParkingSpotRejected(
                    payload(envelope, ParkingSpotRejectedEvent.class));
            default -> log.debug("Ignoring unsupported event type {} on {}", eventType, PARKING_SPOT_TOPIC);
        }
        ack.acknowledge();
    }

    private <T> T payload(EventEnvelope envelope, Class<T> type) throws Exception {
        return objectMapper.treeToValue(envelope.payload(), type);
    }
}
