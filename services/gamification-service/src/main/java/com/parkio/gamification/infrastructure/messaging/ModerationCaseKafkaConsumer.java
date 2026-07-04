package com.parkio.gamification.infrastructure.messaging;

import com.parkio.platform.messaging.EventEnvelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.gamification.application.GamificationApplicationService;
import com.parkio.gamification.application.event.ModerationCaseResolvedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code parkio.moderation.case} (group {@code parkio.gamification}) and applies
 * the admin trust/point penalties carried by {@code ModerationCaseResolved} (actions
 * {@code REDUCE_TRUST} / {@code DEDUCT_POINTS} on USER-targeted cases). Idempotency is
 * enforced by the inbox inside the handler (dedupe by {@code eventId}); the offset is
 * acknowledged only after the handler's transaction commits.
 *
 * <p>{@code ModerationCaseOpened}, appeals and unknown future types are ignored and
 * acked. On failure the record is retried and ultimately dead-lettered by the container's
 * error handler (reuses {@code parkingEventsKafkaListenerContainerFactory} →
 * {@code parkio.dlt.gamification}).
 */
@Component
public class ModerationCaseKafkaConsumer {

    public static final String MODERATION_CASE_TOPIC = "parkio.moderation.case";
    public static final String GROUP = "parkio.gamification";

    private static final String CASE_RESOLVED = "ModerationCaseResolved";

    private static final Logger log = LoggerFactory.getLogger(ModerationCaseKafkaConsumer.class);

    private final GamificationApplicationService gamificationService;
    private final ObjectMapper objectMapper;

    public ModerationCaseKafkaConsumer(GamificationApplicationService gamificationService,
                                       ObjectMapper objectMapper) {
        this.gamificationService = gamificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = MODERATION_CASE_TOPIC,
            groupId = GROUP,
            containerFactory = "parkingEventsKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();

        if (CASE_RESOLVED.equals(eventType)) {
            gamificationService.handleModerationCaseResolved(
                    objectMapper.treeToValue(envelope.payload(), ModerationCaseResolvedEvent.class));
        } else {
            log.debug("Ignoring unsupported event type {} on {}", eventType, MODERATION_CASE_TOPIC);
        }
        ack.acknowledge();
    }
}
