package com.parkio.parking.outcome.normalization;

import com.parkio.parking.domain.VerificationResult;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OutcomeVerificationSignalData(UUID verificationId, VerificationResult result, Instant createdAt) {

    public OutcomeVerificationSignalData {
        Objects.requireNonNull(verificationId, "verificationId");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}