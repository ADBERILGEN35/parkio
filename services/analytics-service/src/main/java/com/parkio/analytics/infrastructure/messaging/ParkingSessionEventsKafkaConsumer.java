package com.parkio.analytics.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.analytics.application.AnalyticsApplicationService;
import com.parkio.analytics.application.ParkingSessionLifecycleMapper;
import com.parkio.analytics.application.ParkingSessionLifecycleValidator;
import com.parkio.analytics.application.event.ParkingSessionCancelledEvent;
import com.parkio.analytics.application.event.ParkingSessionCompletedEvent;
import com.parkio.analytics.application.event.ParkingSessionStartedEvent;
import com.parkio.analytics.domain.exception.AnalyticsContractException;
import com.parkio.platform.messaging.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code parkio.parking.session} (group {@code parkio.analytics}) and projects
 * ParkingSession lifecycle metrics. Idempotency is enforced by the inbox inside each
 * handler (dedupe by {@code eventId}); the offset is acknowledged only after the
 * handler's transaction commits.
 *
 * <p>Supported wire types: {@code ParkingSessionStarted}, {@code ParkingSessionCompleted},
 * {@code ParkingSessionCancelled} (version 1). Unsupported types/versions and malformed
 * contracts throw {@link AnalyticsContractException} and follow the container error
 * handler → DLT ({@code parkio.dlt.analytics}). Spot claim events are not consumed here.
 */
@Component
public class ParkingSessionEventsKafkaConsumer {

    public static final String PARKING_SESSION_TOPIC = "parkio.parking.session";
    public static final String GROUP = "parkio.analytics";

    private static final Logger log = LoggerFactory.getLogger(ParkingSessionEventsKafkaConsumer.class);

    private final AnalyticsApplicationService analyticsService;
    private final ObjectMapper objectMapper;

    public ParkingSessionEventsKafkaConsumer(AnalyticsApplicationService analyticsService,
                                             ObjectMapper objectMapper) {
        this.analyticsService = analyticsService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = PARKING_SESSION_TOPIC,
            groupId = GROUP,
            containerFactory = "gamificationScoreKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();
        if (eventType == null || eventType.isBlank()) {
            throw new AnalyticsContractException("eventType is required on parkio.parking.session");
        }

        switch (eventType) {
            case ParkingSessionLifecycleMapper.WIRE_STARTED -> {
                ParkingSessionStartedEvent payload =
                        payload(envelope, ParkingSessionStartedEvent.class);
                ParkingSessionLifecycleValidator.validateStarted(alignEnvelope(envelope, eventType), payload);
                analyticsService.handleParkingSessionStarted(payload);
            }
            case ParkingSessionLifecycleMapper.WIRE_COMPLETED -> {
                ParkingSessionCompletedEvent payload =
                        payload(envelope, ParkingSessionCompletedEvent.class);
                ParkingSessionLifecycleValidator.validateCompleted(alignEnvelope(envelope, eventType), payload);
                analyticsService.handleParkingSessionCompleted(payload);
            }
            case ParkingSessionLifecycleMapper.WIRE_CANCELLED -> {
                ParkingSessionCancelledEvent payload =
                        payload(envelope, ParkingSessionCancelledEvent.class);
                ParkingSessionLifecycleValidator.validateCancelled(alignEnvelope(envelope, eventType), payload);
                analyticsService.handleParkingSessionCancelled(payload);
            }
            default -> throw new AnalyticsContractException(
                    "Unsupported ParkingSession event type: " + eventType);
        }
        ack.acknowledge();
        log.debug("Processed {} eventId={}", eventType, envelope.eventId());
    }

    /**
     * Prefer the Kafka header eventType when present so validation uses the same type
     * that drove dispatch (headers are authoritative when set by the outbox relay).
     */
    private static EventEnvelope alignEnvelope(EventEnvelope envelope, String eventType) {
        if (eventType.equals(envelope.eventType())) {
            return envelope;
        }
        return new EventEnvelope(
                envelope.eventId(),
                eventType,
                envelope.aggregateType(),
                envelope.aggregateId(),
                envelope.occurredAt(),
                envelope.version(),
                envelope.traceId(),
                envelope.payload());
    }

    private <T> T payload(EventEnvelope envelope, Class<T> type) throws Exception {
        if (envelope.payload() == null || envelope.payload().isNull()) {
            throw new AnalyticsContractException("envelope.payload is required");
        }
        return objectMapper.treeToValue(envelope.payload(), type);
    }
}