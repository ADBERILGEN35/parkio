package com.parkio.auth.application;

import java.time.Instant;
import java.util.UUID;

public record ErasureDurableWorkerClaim(
        UUID requestId,
        UUID authUserId,
        Instant requestedAt,
        String status,
        String durableRecordingStatus,
        UUID claimToken,
        Instant claimExpiresAt) {}
