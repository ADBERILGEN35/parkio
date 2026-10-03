package com.parkio.media.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Restore-replay ACK. Like the live ACK, media queues it only after every stored object of the
 * user is confirmed gone, in the transaction that deletes the erasure job; it echoes the recovery
 * attempt, dataset and erasure-set digest the job carries. {@code MediaOutboxRelay} publishes it
 * to {@code parkio.privacy.erasure}; the payload matches auth's
 * {@code UserErasureRestoreAcknowledgedEvent} (docs/architecture/erasure-restore-replay-contract.md).
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
    public static final String AGGREGATE_TYPE = "AccountErasure";
}
