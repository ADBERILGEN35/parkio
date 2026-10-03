package com.parkio.user.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The coordinator's command to replay one user's erasure during an isolated recovery
 * (docs/architecture/erasure-restore-replay-contract.md).
 */
public record UserErasureRestoreReplayRequestedEvent(
        UUID eventId,
        UUID recoveryAttemptId,
        String restoredDatasetId,
        String erasureSetDigest,
        UUID authUserId,
        Instant erasedAt,
        Instant occurredAt) {

    public static final String TYPE = "UserErasureRestoreReplayRequested";
}
