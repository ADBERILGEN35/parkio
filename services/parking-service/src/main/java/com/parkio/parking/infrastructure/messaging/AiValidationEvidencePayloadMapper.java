package com.parkio.parking.infrastructure.messaging;

import com.parkio.parking.decision.application.EvidenceCollectionService;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.normalization.AiValidationEvidenceInput;
import com.parkio.parking.decision.normalization.EvidenceCollectionRequest;
import com.parkio.parking.decision.normalization.EvidenceNormalizationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Maps {@code AiValidationCompleted} Kafka payload fields to decision-domain normalization
 * inputs. Read-only; does not alter publication behavior.
 */
final class AiValidationEvidencePayloadMapper {

    private AiValidationEvidencePayloadMapper() {}

    static AiValidationEvidenceInput toInput(AiValidationCompletedPayload payload, Instant envelopeOccurredAt) {
        Instant occurredAt = payload.occurredAt() != null ? payload.occurredAt() : envelopeOccurredAt;
        if (occurredAt == null) {
            throw new EvidenceNormalizationException("AI validation occurredAt is required for normalization");
        }
        return AiValidationEvidenceInput.of(
                payload.eventId(),
                payload.mediaId(),
                payload.parkingSpotId(),
                payload.status(),
                payload.detectedRiskTypes(),
                payload.emptySpaceConfidence(),
                payload.legalRiskScore(),
                payload.imageQualityScore(),
                payload.aiConfidence(),
                occurredAt);
    }

    /**
     * Full payload shape from ai-validation-service {@code AiValidationCompletedEvent}.
     * Score fields are optional at the consumer boundary for backward-compatible parsing.
     */
    record AiValidationCompletedPayload(
            UUID eventId,
            UUID mediaId,
            UUID parkingSpotId,
            String status,
            List<String> detectedRiskTypes,
            Integer emptySpaceConfidence,
            Integer legalRiskScore,
            Integer imageQualityScore,
            Integer aiConfidence,
            Instant occurredAt) {
    }

    static void observeEvidenceShadow(AiValidationCompletedPayload payload, Instant envelopeOccurredAt) {
        if (payload.parkingSpotId() == null) {
            return;
        }
        try {
            AiValidationEvidenceInput input = toInput(payload, envelopeOccurredAt);
            EvidenceCollectionRequest request = new EvidenceCollectionRequest(
                    input.parkingSpotId(),
                    input.eventId(),
                    input.occurredAt(),
                    input,
                    null);
            EvidenceVector vector = new EvidenceCollectionService().collect(request);
            if (log.isDebugEnabled()) {
                log.debug(
                        "Shadow evidence vector spotId={} evaluationId={} itemCount={}",
                        vector.parkingSpotId(),
                        vector.evaluationId(),
                        vector.size());
            }
        } catch (EvidenceNormalizationException ex) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "Shadow evidence normalization skipped spotId={} eventId={} reason={}",
                        payload.parkingSpotId(),
                        payload.eventId(),
                        ex.getMessage());
            }
        } catch (RuntimeException ex) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "Shadow evidence normalization failed spotId={} eventId={}",
                        payload.parkingSpotId(),
                        payload.eventId(),
                        ex);
            }
        }
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AiValidationEvidencePayloadMapper.class);
}
