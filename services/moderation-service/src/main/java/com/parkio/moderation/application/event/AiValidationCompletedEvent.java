package com.parkio.moderation.application.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Local copy of ai-validation-service's {@code AiValidationCompleted} payload
 * (event-contracts.md). Contracts are duplicated, never shared (ai-context/01).
 *
 * <p>The AI result is <strong>advisory</strong> (ai-context/02): moderation derives a
 * decision from the real fields ({@code status}, {@code detectedRiskTypes}, scores) —
 * it never auto-rejects. {@code status} and {@code detectedRiskTypes} are carried as
 * strings/string-list so unknown future enum values deserialize safely. {@code
 * parkingSpotId} is nullable: a standalone media validation has no spot.
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
        Instant occurredAt) {

    /** Non-null view of the detected risk types (the field may be absent/null on the wire). */
    public List<String> riskTypesOrEmpty() {
        return detectedRiskTypes == null ? List.of() : detectedRiskTypes;
    }
}
