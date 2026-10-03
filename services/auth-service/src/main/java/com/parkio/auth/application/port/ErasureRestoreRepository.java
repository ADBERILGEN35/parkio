package com.parkio.auth.application.port;

import com.parkio.auth.application.durable.ErasureLedgerEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Restore replay attempts and their attempt-bound participant ACKs
 * (docs/architecture/erasure-restore-replay-contract.md). Separate from the request-scoped
 * live ACKs.
 */
public interface ErasureRestoreRepository {

    /** One recovery attempt: the restored dataset and the digest of the erasure set it replays. */
    record RestoreAttempt(UUID recoveryAttemptId, String restoredDatasetId, String erasureSetDigest,
                          int userCount, Instant startedAt) {
    }

    Optional<RestoreAttempt> findAttempt(UUID recoveryAttemptId);

    void insertAttempt(RestoreAttempt attempt, List<ErasureLedgerEntry> users);

    boolean isAttemptUser(UUID recoveryAttemptId, UUID authUserId);

    /** Records the participant's latest status for one user of the attempt. */
    void upsertAck(UUID recoveryAttemptId, UUID authUserId, String serviceName, String status, Instant ackedAt);

    /** Per participant: how many users of the attempt it acknowledged with {@code status}. */
    Map<String, Long> countAcksByService(UUID recoveryAttemptId, String status);
}
