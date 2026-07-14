package com.parkio.auth.application.result;

import com.parkio.auth.domain.RefreshTokenRevocationReason;
import java.time.Instant;
import java.util.UUID;

public record AdminSessionSummary(
        UUID sessionId,
        Instant createdAt,
        boolean revoked,
        RefreshTokenRevocationReason revokedReason,
        Instant expiresAt) {
}
