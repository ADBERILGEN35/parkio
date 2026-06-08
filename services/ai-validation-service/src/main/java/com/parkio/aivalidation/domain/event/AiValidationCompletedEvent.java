package com.parkio.aivalidation.domain.event;

import com.parkio.aivalidation.domain.AiRiskType;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.AiValidationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Advisory event emitted when a validation completes (ai-context/02: advisory, not a
 * decision). Written to the transactional outbox and consumed by moderation/parking.
 * Partitioned by {@code mediaId} (always present; {@code parkingSpotId} may be null).
 *
 * <p>Enums serialize as their name; {@code detectedRiskTypes} is a JSON array of risk
 * type names. Pure record — serialization lives in the infrastructure outbox adapter.
 */
public record AiValidationCompletedEvent(
        UUID eventId,
        UUID mediaId,
        UUID parkingSpotId,
        AiValidationStatus status,
        int emptySpaceConfidence,
        int legalRiskScore,
        int imageQualityScore,
        int aiConfidence,
        List<AiRiskType> detectedRiskTypes,
        Instant occurredAt) {

    public static final String AGGREGATE_TYPE = "AiValidationResult";
    public static final String TYPE = "AiValidationCompleted";

    public static AiValidationCompletedEvent of(AiValidationResult result, Instant now) {
        return new AiValidationCompletedEvent(
                UUID.randomUUID(),
                result.mediaId(),
                result.parkingSpotId().orElse(null),
                result.status(),
                result.emptySpaceConfidence(),
                result.legalRiskScore(),
                result.imageQualityScore(),
                result.aiConfidence(),
                result.detectedRiskTypes(),
                now);
    }

    /** Partition key / aggregate id for the outbox envelope: the media id. */
    public UUID aggregateId() {
        return mediaId;
    }
}
