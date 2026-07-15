package com.parkio.parking.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.ParkingApplicationService;
import com.parkio.platform.messaging.EventEnvelope;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
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
 * Applies AI validation results as the parking publication gate. Spots stay
 * non-discoverable until AI passes; uncertain to pending review; failed / non-parking
 * to rejected. Failures and missing parkingSpotId are fail-closed (no publication).
 */
@Component
@ConditionalOnProperty(
        name = "parkio.kafka.ai-validation-consumer.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AiValidationEventsKafkaConsumer {

    public static final String TOPIC = "parkio.aivalidation.result";
    public static final String GROUP = "parkio.parking";

    private static final String AI_VALIDATION_COMPLETED = "AiValidationCompleted";
    private static final Logger log = LoggerFactory.getLogger(AiValidationEventsKafkaConsumer.class);

    private final ParkingApplicationService parking;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AiValidationEventsKafkaConsumer(
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
            if (AI_VALIDATION_COMPLETED.equals(eventType)) {
                AiValidationCompleted event =
                        objectMapper.treeToValue(envelope.payload(), AiValidationCompleted.class);
                if (event.parkingSpotId() != null && markProcessing(event.eventId(), eventType)) {
                    parking.applyAiValidationResult(
                            event.parkingSpotId(),
                            event.status(),
                            event.detectedRiskTypes() == null ? List.of() : event.detectedRiskTypes());
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

    /**
     * Minimal payload shape from ai-validation-service {@code AiValidationCompleted}.
     * Status and risk types are strings so unknown values fail closed in the
     * application service instead of breaking deserialization.
     */
    private record AiValidationCompleted(
            UUID eventId,
            UUID mediaId,
            UUID parkingSpotId,
            String status,
            List<String> detectedRiskTypes) {
    }
}