package com.parkio.aivalidation.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.aivalidation.application.AiValidationApplicationService;
import com.parkio.aivalidation.application.event.ParkingSpotCreatedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code parkio.parking.spot} (group {@code parkio.aivalidation}) and runs an
 * advisory validation for a newly created spot's photo. Idempotency is enforced by the
 * inbox inside the handler (dedupe by {@code eventId}); the offset is acknowledged only
 * after the handler's transaction commits.
 *
 * <p>Only {@code ParkingSpotCreated} is handled; all other parking-spot lifecycle events
 * and unknown future types are ignored and acked. On failure the record is retried and
 * ultimately dead-lettered by the container's error handler (reuses the service's
 * {@code mediaEventsKafkaListenerContainerFactory} → {@code parkio.dlt.aivalidation}).
 */
@Component
public class ParkingEventsKafkaConsumer {

    public static final String PARKING_SPOT_TOPIC = "parkio.parking.spot";
    public static final String GROUP = "parkio.aivalidation";

    private static final String SPOT_CREATED = "ParkingSpotCreated";

    private static final Logger log = LoggerFactory.getLogger(ParkingEventsKafkaConsumer.class);

    private final AiValidationApplicationService validationService;
    private final ObjectMapper objectMapper;

    public ParkingEventsKafkaConsumer(AiValidationApplicationService validationService,
                                      ObjectMapper objectMapper) {
        this.validationService = validationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = PARKING_SPOT_TOPIC,
            groupId = GROUP,
            containerFactory = "mediaEventsKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();

        if (SPOT_CREATED.equals(eventType)) {
            validationService.handleParkingSpotCreated(
                    objectMapper.treeToValue(envelope.payload(), ParkingSpotCreatedEvent.class));
        } else {
            log.debug("Ignoring unsupported event type {} on {}", eventType, PARKING_SPOT_TOPIC);
        }
        ack.acknowledge();
    }
}
