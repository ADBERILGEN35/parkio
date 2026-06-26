package com.parkio.notification.infrastructure.messaging;

import com.parkio.platform.messaging.EventEnvelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.notification.application.NotificationApplicationService;
import com.parkio.notification.application.event.AppealResolvedEvent;
import com.parkio.notification.application.event.ModerationCaseResolvedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code parkio.moderation.case} (group {@code parkio.notification}) and notifies
 * the affected user when an appeal or a USER-targeted case is resolved. Idempotency is
 * enforced by the inbox inside each handler (dedupe by {@code eventId}); the offset is
 * acknowledged only after the handler's transaction commits.
 *
 * <p>{@code ModerationCaseOpened}, {@code AppealCreated} and unknown future types are
 * ignored and acked. On failure the record is retried and ultimately dead-lettered
 * (reuses {@code gamificationScoreKafkaListenerContainerFactory} →
 * {@code parkio.dlt.notification}).
 */
@Component
public class ModerationCaseKafkaConsumer {

    public static final String MODERATION_CASE_TOPIC = "parkio.moderation.case";
    public static final String GROUP = "parkio.notification";

    private static final String APPEAL_RESOLVED = "AppealResolved";
    private static final String CASE_RESOLVED = "ModerationCaseResolved";

    private static final Logger log = LoggerFactory.getLogger(ModerationCaseKafkaConsumer.class);

    private final NotificationApplicationService notificationService;
    private final ObjectMapper objectMapper;

    public ModerationCaseKafkaConsumer(NotificationApplicationService notificationService,
                                       ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = MODERATION_CASE_TOPIC,
            groupId = GROUP,
            containerFactory = "gamificationScoreKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record,
                          @Header(name = "eventType", required = false) String eventTypeHeader,
                          Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = eventTypeHeader != null ? eventTypeHeader : envelope.eventType();

        switch (eventType == null ? "" : eventType) {
            case APPEAL_RESOLVED -> notificationService.handleAppealResolved(
                    objectMapper.treeToValue(envelope.payload(), AppealResolvedEvent.class));
            case CASE_RESOLVED -> notificationService.handleModerationCaseResolved(
                    objectMapper.treeToValue(envelope.payload(), ModerationCaseResolvedEvent.class));
            default -> log.debug("Ignoring unsupported event type {} on {}", eventType, MODERATION_CASE_TOPIC);
        }
        ack.acknowledge();
    }
}
