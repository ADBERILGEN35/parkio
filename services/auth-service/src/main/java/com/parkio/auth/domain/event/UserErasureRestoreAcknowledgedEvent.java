package com.parkio.auth.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A participant's acknowledgement that it replayed one user's erasure for a recovery attempt.
 * It echoes the attempt, dataset and erasure-set digest it was asked for; the coordinator decides
 * whether they match the attempt (docs/architecture/erasure-restore-replay-contract.md).
 */
public record UserErasureRestoreAcknowledgedEvent(
        UUID eventId,
        UUID recoveryAttemptId,
        String restoredDatasetId,
        String erasureSetDigest,
        UUID authUserId,
        String serviceName,
        String status,
        Instant occurredAt) {

    public static final String TYPE = "UserErasureRestoreAcknowledged";
}
