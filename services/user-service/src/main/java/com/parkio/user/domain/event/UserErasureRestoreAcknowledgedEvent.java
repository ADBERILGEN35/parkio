package com.parkio.user.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Restore-replay ACK written to the transactional outbox in the transaction that replayed the
 * erase, so a SUCCESS exists only if the replay committed. It echoes the recovery attempt,
 * dataset and erasure-set digest it was asked for. {@code UserOutboxRelay} publishes it to
 * {@code parkio.privacy.erasure}; the payload matches auth's {@code UserErasureRestoreAcknowledgedEvent}
 * (docs/architecture/erasure-restore-replay-contract.md).
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
