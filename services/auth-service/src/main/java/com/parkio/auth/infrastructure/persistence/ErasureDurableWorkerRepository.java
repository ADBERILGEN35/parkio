package com.parkio.auth.infrastructure.persistence;

import com.parkio.auth.application.ErasureDurableWorkerClaim;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ErasureDurableWorkerRepository {

    /**
     * Claims a batch of erasure requests needing durable persist or completion reconciliation.
     * Uses {@code FOR UPDATE SKIP LOCKED} and sets a lease token; the transaction must commit
     * before store I/O so locks are not held during external puts.
     */
    List<ErasureDurableWorkerClaim> claimBatch(
            Instant now, int limit, int maxAttempts, Instant claimExpiresAt, UUID claimToken);

    /** Schedules the first worker retry after a post-commit persist failure (no lease). */
    int scheduleInitialPersistRetry(UUID requestId, Instant nextAttemptAt, String errorCode);

    /** Clears the worker lease after successful processing. Returns rows updated (0 if stale). */
    int releaseClaim(UUID requestId, UUID claimToken, Instant asOf);

    /**
     * Records a failed attempt, schedules the next retry, and clears the lease.
     * Returns rows updated (0 if stale).
     */
    int recordRetryScheduled(
            UUID requestId,
            UUID claimToken,
            int newAttemptCount,
            Instant nextAttemptAt,
            String errorCode);

    /**
     * Marks the row durably recorded only when the lease token still matches.
     * Returns rows updated (0 if stale).
     */
    int markDurablyRecordedIfClaimed(UUID requestId, UUID claimToken, Instant asOf);
}
