package com.parkio.parking.decision.normalization;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Inputs required to assemble one {@link com.parkio.parking.decision.evidence.EvidenceVector}
 * without fetching remote state.
 */
public record EvidenceCollectionRequest(
        UUID parkingSpotId,
        UUID evaluationId,
        Instant collectedAt,
        AiValidationEvidenceInput aiValidation,
        ParkingSpotEvidenceContext spotContext) {

    public EvidenceCollectionRequest {
        Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        Objects.requireNonNull(evaluationId, "evaluationId");
        Objects.requireNonNull(collectedAt, "collectedAt");
        Objects.requireNonNull(aiValidation, "aiValidation");
        if (!parkingSpotId.equals(aiValidation.parkingSpotId())) {
            throw new EvidenceNormalizationException(
                    "parkingSpotId mismatch between request and AI validation input");
        }
        if (!evaluationId.equals(aiValidation.eventId())) {
            throw new EvidenceNormalizationException(
                    "evaluationId must match AI validation eventId");
        }
        if (spotContext != null && !parkingSpotId.equals(spotContext.parkingSpotId())) {
            throw new EvidenceNormalizationException(
                    "parkingSpotId mismatch between request and parking spot context");
        }
    }

    public Optional<ParkingSpotEvidenceContext> optionalSpotContext() {
        return Optional.ofNullable(spotContext);
    }
}
