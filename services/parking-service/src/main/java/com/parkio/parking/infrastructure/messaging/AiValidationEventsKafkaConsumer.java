package com.parkio.parking.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.DecisionAuthorityApplicationService;
import com.parkio.parking.application.DecisionShadowOrchestrator;
import com.parkio.parking.application.result.AiValidationApplyOutcome;
import com.parkio.parking.application.result.ControlledAuthorityApplyResult;
import com.parkio.parking.decision.normalization.AiValidationEvidenceInput;
import com.parkio.platform.messaging.EventEnvelope;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
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
 *
 * <p>WP-05.8 controlled authority (default off) may apply Decision Engine FULL_PUBLISH
 * for a deterministic canary cohort. Non-selected traffic uses the legacy path.
 * Optional Decision Engine shadow runs after legacy applies only (never after
 * authoritative Decision Engine apply).
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

    private final DecisionAuthorityApplicationService authority;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final DecisionShadowOrchestrator decisionShadow;

    public AiValidationEventsKafkaConsumer(
            DecisionAuthorityApplicationService authority,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            Clock clock,
            DecisionShadowOrchestrator decisionShadow) {
        this.authority = authority;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.decisionShadow = decisionShadow;
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
                AiValidationEvidencePayloadMapper.AiValidationCompletedPayload payload =
                        objectMapper.treeToValue(
                                envelope.payload(),
                                AiValidationEvidencePayloadMapper.AiValidationCompletedPayload.class);
                if (payload.parkingSpotId() != null && markProcessing(payload.eventId(), eventType)) {
                    AiValidationEvidenceInput evidenceInput =
                            AiValidationEvidencePayloadMapper.toInput(payload, envelope.occurredAt());
                    ControlledAuthorityApplyResult controlled = authority.applyAiValidation(
                            payload.parkingSpotId(),
                            payload.status(),
                            payload.detectedRiskTypes() == null ? List.of() : payload.detectedRiskTypes(),
                            payload.eventId(),
                            envelope.occurredAt(),
                            evidenceInput);
                    AiValidationApplyOutcome applyOutcome = controlled.applyOutcome();
                    AiValidationEvidencePayloadMapper.observeEvidenceShadow(payload, envelope.occurredAt());
                    if (!controlled.authorityApplied()) {
                        observeDecisionShadow(payload, envelope.occurredAt(), applyOutcome);
                    }
                }
            } else {
                log.debug("Ignoring unsupported event type {} on {}", eventType, TOPIC);
            }
            ack.acknowledge();
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void observeDecisionShadow(
            AiValidationEvidencePayloadMapper.AiValidationCompletedPayload payload,
            Instant envelopeOccurredAt,
            AiValidationApplyOutcome applyOutcome) {
        if (!decisionShadow.isEnabled()) {
            return;
        }
        try {
            AiValidationEvidenceInput input =
                    AiValidationEvidencePayloadMapper.toInput(payload, envelopeOccurredAt);
            decisionShadow.observeAfterApply(input, applyOutcome, clock.instant());
        } catch (RuntimeException ex) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "Decision shadow orchestration skipped spotId={} eventId={}",
                        payload.parkingSpotId(),
                        payload.eventId(),
                        ex);
            }
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
}