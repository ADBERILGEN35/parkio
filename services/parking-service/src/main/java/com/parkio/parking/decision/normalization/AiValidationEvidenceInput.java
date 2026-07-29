package com.parkio.parking.decision.normalization;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Boundary input for AI validation evidence normalization. Framework-free; populated
 * from {@code parkio.aivalidation.result} payload fields at the infrastructure edge.
 */
public record AiValidationEvidenceInput(
        UUID eventId,
        UUID mediaId,
        UUID parkingSpotId,
        String status,
        List<String> detectedRiskTypes,
        Integer emptySpaceConfidence,
        Integer legalRiskScore,
        Integer imageQualityScore,
        Integer aiConfidence,
        Instant occurredAt,
        int payloadSchemaVersion) {

    public static final int SUPPORTED_PAYLOAD_SCHEMA_VERSION = 1;

    public AiValidationEvidenceInput {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(mediaId, "mediaId");
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        detectedRiskTypes = detectedRiskTypes == null ? List.of() : List.copyOf(detectedRiskTypes);
        if (payloadSchemaVersion < 1) {
            throw new EvidenceNormalizationException("payloadSchemaVersion must be >= 1");
        }
        if (payloadSchemaVersion > SUPPORTED_PAYLOAD_SCHEMA_VERSION) {
            throw new EvidenceNormalizationException(
                    "unsupported AI validation payload schema version: " + payloadSchemaVersion);
        }
    }

    public static AiValidationEvidenceInput of(
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
        return new AiValidationEvidenceInput(
                eventId,
                mediaId,
                parkingSpotId,
                status,
                detectedRiskTypes,
                emptySpaceConfidence,
                legalRiskScore,
                imageQualityScore,
                aiConfidence,
                occurredAt,
                SUPPORTED_PAYLOAD_SCHEMA_VERSION);
    }
}
