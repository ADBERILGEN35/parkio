package com.parkio.aivalidation.presentation.dto;

import com.parkio.aivalidation.domain.AiRiskType;
import com.parkio.aivalidation.domain.AiValidationFinding;
import com.parkio.aivalidation.domain.AiValidationType;
import java.time.Instant;
import java.util.UUID;

/** Response view of a single advisory finding. */
public record FindingResponse(
        UUID id,
        AiValidationType validationType,
        AiRiskType riskType,
        int score,
        String message,
        Instant createdAt) {

    public static FindingResponse from(AiValidationFinding f) {
        return new FindingResponse(f.id(), f.validationType(), f.riskType().orElse(null),
                f.score(), f.message(), f.createdAt());
    }
}
