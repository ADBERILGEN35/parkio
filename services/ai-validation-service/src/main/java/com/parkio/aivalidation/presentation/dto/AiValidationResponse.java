package com.parkio.aivalidation.presentation.dto;

import com.parkio.aivalidation.domain.AiRiskType;
import com.parkio.aivalidation.domain.AiValidationDecision;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.AiValidationStatus;
import com.parkio.aivalidation.domain.DeterministicAiValidator;
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
        AiValidationDecision decision,
        String reasonCode,
        String claimedRegionAssessment,
        String vehicleFitEstimate,
        String obstructionAssessment,
        String legalityAccessAssessment,
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
                r.status(), AiValidationDecision.from(r.status()),
                findingSuffix(r, DeterministicAiValidator.REASON_CODE_PREFIX),
                findingSuffix(r, DeterministicAiValidator.ASSESSMENT_CLAIMED_REGION_PREFIX),
                findingSuffix(r, DeterministicAiValidator.ASSESSMENT_VEHICLE_FIT_PREFIX),
                findingSuffix(r, DeterministicAiValidator.ASSESSMENT_OBSTRUCTION_PREFIX),
                findingSuffix(r, DeterministicAiValidator.ASSESSMENT_LEGALITY_PREFIX),
                r.emptySpaceConfidence(), r.legalRiskScore(), r.imageQualityScore(), r.aiConfidence(),
                r.detectedRiskTypes(),
                r.findings().stream().map(FindingResponse::from).toList(),
                r.vehicleFitEstimates().stream().map(VehicleFitResponse::from).toList(),
                r.createdAt());
    }

    private static String findingSuffix(AiValidationResult r, String prefix) {
        return r.findings().stream()
                .map(f -> f.message())
                .filter(m -> m != null && m.startsWith(prefix))
                .map(m -> m.substring(prefix.length()))
                .findFirst()
                .orElse(null);
    }
}
