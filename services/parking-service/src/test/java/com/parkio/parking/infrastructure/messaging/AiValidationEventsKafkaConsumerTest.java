package com.parkio.parking.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.parkio.parking.application.DecisionAuthorityApplicationService;
import com.parkio.parking.application.DecisionShadowOrchestrator;
import com.parkio.parking.application.port.DecisionShadowObserverPort;
import com.parkio.parking.application.result.AiValidationApplyOutcome;
import com.parkio.parking.application.result.ControlledAuthorityApplyResult;
import com.parkio.parking.decision.application.EvidenceCollectionService;
import com.parkio.parking.decision.authority.AuthorityEligibilityReason;
import com.parkio.parking.decision.authority.AuthorityFallbackReason;
import com.parkio.parking.decision.policy.DecisionEngine;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;

/** Unit tests for AI-validation to parking publication-gate consumer. */
class AiValidationEventsKafkaConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final DecisionAuthorityApplicationService authority =
            mock(DecisionAuthorityApplicationService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final DecisionShadowOrchestrator decisionShadow = new DecisionShadowOrchestrator(
            false, new DecisionEngine(), new EvidenceCollectionService(), DecisionShadowObserverPort.noop());
    private final AiValidationEventsKafkaConsumer consumer = new AiValidationEventsKafkaConsumer(
            authority,
            jdbc,
            objectMapper,
            Clock.fixed(Instant.parse("2026-06-08T12:00:00Z"), ZoneOffset.UTC),
            decisionShadow);

    @Test
    void dispatchesCompletedEventWithParkingSpotId() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID spotId = UUID.randomUUID();
        when(jdbc.update(any(String.class), eq(eventId), eq("AiValidationCompleted"), any()))
                .thenReturn(1);
        when(authority.applyAiValidation(any(), any(), any(), any(), any(), any()))
                .thenReturn(ControlledAuthorityApplyResult.legacy(
                        new AiValidationApplyOutcome(
                                ParkingSpotStatus.PENDING_VALIDATION,
                                ParkingSpotStatus.ACTIVE,
                                AiValidationApplyOutcome.Kind.APPLIED),
                        AuthorityEligibilityReason.AUTHORITY_DISABLED,
                        AuthorityFallbackReason.NOT_SELECTED));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("mediaId", UUID.randomUUID().toString());
        payload.put("parkingSpotId", spotId.toString());
        payload.put("status", "PASSED");
        ArrayNode risks = payload.putArray("detectedRiskTypes");
        risks.add("LOW_IMAGE_QUALITY");

        consumer.onMessage(record(eventId, "AiValidationCompleted", payload), "AiValidationCompleted", null, ack);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> risksCaptor = ArgumentCaptor.forClass(List.class);
        verify(authority).applyAiValidation(
                eq(spotId),
                eq("PASSED"),
                risksCaptor.capture(),
                eq(eventId),
                eq(Instant.parse("2026-06-08T12:00:00Z")),
                any());
        assertThat(risksCaptor.getValue()).containsExactly("LOW_IMAGE_QUALITY");
        verify(ack).acknowledge();
    }

    @Test
    void skipsWhenParkingSpotIdMissing() throws Exception {
        UUID eventId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("mediaId", UUID.randomUUID().toString());
        payload.putNull("parkingSpotId");
        payload.put("status", "PASSED");

        consumer.onMessage(record(eventId, "AiValidationCompleted", payload), "AiValidationCompleted", null, ack);

        verify(authority, never()).applyAiValidation(any(), any(), any(), any(), any(), any());
        verify(jdbc, never()).update(any(String.class), any(), any(), any());
        verify(ack).acknowledge();
    }

    private ConsumerRecord<String, String> record(UUID eventId, String eventType, ObjectNode payload)
            throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("aggregateType", "AiValidationResult");
        envelope.put("aggregateId", UUID.randomUUID().toString());
        envelope.put("occurredAt", "2026-06-08T12:00:00Z");
        envelope.put("version", 1);
        envelope.set("payload", payload);
        return new ConsumerRecord<>("parkio.aivalidation.result", 0, 0L,
                eventId.toString(), objectMapper.writeValueAsString(envelope));
    }
}