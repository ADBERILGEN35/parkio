package com.parkio.aivalidation.domain.event;

import com.parkio.aivalidation.domain.AiRiskType;
import com.parkio.aivalidation.domain.AiValidationFinding;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.AiValidationStatus;
import com.parkio.aivalidation.domain.DeterministicAiValidator;
import com.parkio.aivalidation.domain.ModerationProvenance;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Advisory event emitted when a validation completes (ai-context/02: advisory, not a
 * decision). Written to the transactional outbox and consumed by moderation/parking.
 * Partitioned by {@code mediaId} (always present; {@code parkingSpotId} may be null).
 *
 * <p>{@code rejectionReasonCode} and {@code policyVersion} are additive optional fields
 * for consumers that understand structured rejection / policy cutoffs. Older consumers
 * ignore unknown JSON properties.
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
        Instant occurredAt,
        String rejectionReasonCode,
        String policyVersion) {

    public static final String AGGREGATE_TYPE = "AiValidationResult";
    public static final String TYPE = "AiValidationCompleted";

    public AiValidationCompletedEvent(
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
        this(eventId, mediaId, parkingSpotId, status, emptySpaceConfidence, legalRiskScore,
                imageQualityScore, aiConfidence, detectedRiskTypes, occurredAt, null, null);
    }

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
                now,
                findingSuffix(result, DeterministicAiValidator.REJECTION_REASON_CODE_PREFIX),
                policyVersionOf(result));
    }

    /** Partition key / aggregate id for the outbox envelope: the media id. */
    public UUID aggregateId() {
        return mediaId;
    }

    private static String findingSuffix(AiValidationResult result, String prefix) {
        return result.findings().stream()
                .map(AiValidationFinding::message)
                .filter(m -> m != null && m.startsWith(prefix))
                .map(m -> m.substring(prefix.length()))
                .findFirst()
                .orElse(null);
    }

    private static String policyVersionOf(AiValidationResult result) {
        ModerationProvenance provenance = result.provenance();
        return provenance == null ? null : provenance.policyVersion();
    }
}
