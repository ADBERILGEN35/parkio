package com.parkio.user.presentation.dto;

import com.parkio.user.application.result.SmartReturnCheckCandidate;
import java.time.Instant;
import java.util.UUID;

public record SmartReturnCheckCandidateResponse(
        UUID userId,
        double homeLatitude,
        double homeLongitude,
        String homeLabel,
        int radiusMeters,
        Instant expectedReturnAt,
        boolean claimRetried) {

    public static SmartReturnCheckCandidateResponse from(SmartReturnCheckCandidate candidate) {
        return new SmartReturnCheckCandidateResponse(candidate.userId(), candidate.homeLatitude(),
                candidate.homeLongitude(), candidate.homeLabel(), candidate.radiusMeters(),
                candidate.expectedReturnAt(), candidate.claimRetried());
    }
}
