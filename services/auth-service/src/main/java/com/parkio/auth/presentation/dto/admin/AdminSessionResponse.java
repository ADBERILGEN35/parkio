package com.parkio.auth.presentation.dto.admin;

import com.parkio.auth.domain.RefreshTokenRevocationReason;
import java.time.Instant;
import java.util.UUID;

public record AdminSessionResponse(
        UUID sessionId,
        Instant createdAt,
        boolean revoked,
        RefreshTokenRevocationReason revokedReason,
        Instant expiresAt) {
}
