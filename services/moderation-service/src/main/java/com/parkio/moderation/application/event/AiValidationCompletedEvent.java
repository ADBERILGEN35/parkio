package com.parkio.moderation.application.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Local copy of ai-validation-service's {@code AiValidationCompleted} payload
 * (event-contracts.md). Contracts are duplicated, never shared (ai-context/01).
 *
 * <p>{@code rejectionReasonCode} and {@code policyVersion} are additive optional fields.
 */
public record AiValidationCompletedEvent(
        UUID eventId,
        UUID mediaId,
        UUID parkingSpotId,
        String status,
        int emptySpaceConfidence,
        int legalRiskScore,
        int imageQualityScore,
        int aiConfidence,
        List<String> detectedRiskTypes,
        Instant occurredAt,
        String rejectionReasonCode,
        String policyVersion) {

    public AiValidationCompletedEvent(
            UUID eventId,
            UUID mediaId,
            UUID parkingSpotId,
            String status,
            int emptySpaceConfidence,
            int legalRiskScore,
            int imageQualityScore,
            int aiConfidence,
            List<String> detectedRiskTypes,
            Instant occurredAt) {
        this(eventId, mediaId, parkingSpotId, status, emptySpaceConfidence, legalRiskScore,
                imageQualityScore, aiConfidence, detectedRiskTypes, occurredAt, null, null);
    }

    /** Non-null view of the detected risk types (the field may be absent/null on the wire). */
    public List<String> riskTypesOrEmpty() {
        return detectedRiskTypes == null ? List.of() : detectedRiskTypes;
    }
}
