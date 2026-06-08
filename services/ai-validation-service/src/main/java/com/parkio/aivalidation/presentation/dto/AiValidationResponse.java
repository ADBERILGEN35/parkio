package com.parkio.aivalidation.presentation.dto;

import com.parkio.aivalidation.domain.AiRiskType;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.AiValidationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response view of an advisory validation result with its findings and fit estimates. */
public record AiValidationResponse(
        UUID id,
        UUID mediaId,
        UUID parkingSpotId,
        UUID requestedByUserId,
        AiValidationStatus status,
        int emptySpaceConfidence,
        int legalRiskScore,
        int imageQualityScore,
        int aiConfidence,
        List<AiRiskType> detectedRiskTypes,
        List<FindingResponse> findings,
        List<VehicleFitResponse> vehicleFitEstimates,
        Instant createdAt) {

    public static AiValidationResponse from(AiValidationResult r) {
        return new AiValidationResponse(
                r.id(), r.mediaId(), r.parkingSpotId().orElse(null), r.requestedByUserId().orElse(null),
                r.status(), r.emptySpaceConfidence(), r.legalRiskScore(), r.imageQualityScore(), r.aiConfidence(),
                r.detectedRiskTypes(),
                r.findings().stream().map(FindingResponse::from).toList(),
                r.vehicleFitEstimates().stream().map(VehicleFitResponse::from).toList(),
                r.createdAt());
    }
}
