package com.parkio.auth.domain.event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Command to replay one user's erasure in every participant during an isolated recovery,
 * bound to the recovery attempt, the restored dataset and the erasure set
 * (docs/architecture/erasure-restore-replay-contract.md). Ids only, no profile data.
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
    public static final String AGGREGATE_TYPE = "AccountErasure";

    /** One replay command per attempt and user: restarting the same attempt re-derives it. */
    public static UUID eventIdFor(UUID recoveryAttemptId, UUID authUserId) {
        String key = recoveryAttemptId + ":" + authUserId + ":restore-replay";
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
