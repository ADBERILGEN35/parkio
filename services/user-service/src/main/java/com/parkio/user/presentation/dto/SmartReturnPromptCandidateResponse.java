package com.parkio.user.presentation.dto;

import com.parkio.user.application.result.SmartReturnPromptCandidate;
import java.util.UUID;

public record SmartReturnPromptCandidateResponse(UUID userId) {

    public static SmartReturnPromptCandidateResponse from(SmartReturnPromptCandidate candidate) {
        return new SmartReturnPromptCandidateResponse(candidate.userId());
    }
}
