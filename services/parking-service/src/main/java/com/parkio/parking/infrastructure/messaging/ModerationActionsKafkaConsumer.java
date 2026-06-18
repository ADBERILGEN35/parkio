package com.parkio.parking.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.ParkingApplicationService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies authoritative moderator rejections to parking state. No parking event is
 * emitted, so this consumer cannot close the parking-to-moderation event loop.
 */
@Component
@ConditionalOnProperty(
        name = "parkio.kafka.moderation-consumer.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ModerationActionsKafkaConsumer {

    public static final String TOPIC = "parkio.moderation.action";
    public static final String GROUP = "parkio.parking";

    private static final String REJECTED_BY_MODERATOR = "ParkingSpotRejectedByModerator";
    private static final Logger log = LoggerFactory.getLogger(ModerationActionsKafkaConsumer.class);

    private final ParkingApplicationService parking;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ModerationActionsKafkaConsumer(
            ParkingApplicationService parking,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            Clock clock) {
        this.parking = parking;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    @KafkaListener(
            topics = TOPIC,
            groupId = GROUP,
            containerFactory = "parkingKafkaListenerContainerFactory")
    public void onMessage(
            ConsumerRecord<String, String> record,
            @Header(name = "eventType", required = false) String eventTypeHeader,
            @Header(name = "traceId", required = false) String traceIdHeader,
            Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();
        String traceId = traceIdHeader != null ? traceIdHeader : envelope.traceId();
        if (traceId != null) {
            MDC.put("correlationId", traceId);
        }
        try {
            if (REJECTED_BY_MODERATOR.equals(eventType)) {
                ModeratorRejection event =
                        objectMapper.treeToValue(envelope.payload(), ModeratorRejection.class);
                if (markProcessing(event.eventId(), eventType)) {
                    parking.rejectSpotByModerator(event.parkingSpotId());
                }
            } else {
                log.debug("Ignoring unsupported event type {} on {}", eventType, TOPIC);
            }
            ack.acknowledge();
        } finally {
            MDC.remove("correlationId");
        }
    }

    private boolean markProcessing(UUID eventId, String eventType) {
        return jdbc.update(
                """
                INSERT INTO inbox_events (id, event_type, processed_at)
                VALUES (?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                eventId,
                eventType,
                Timestamp.from(clock.instant())) == 1;
    }

    private record ModeratorRejection(UUID eventId, UUID parkingSpotId) {
    }
}
