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
     *
     * <p>Includes {@code FAILED_RETRYING} rows: durable recording is independent of temporary
     * participant failures. Does not permanently exclude rows by attempt count.
     */
    List<ErasureDurableWorkerClaim> claimBatch(
            Instant now, int limit, Instant claimExpiresAt, UUID claimToken);

    /** Schedules the first worker retry after a post-commit persist failure (no lease). */
    int scheduleInitialPersistRetry(UUID requestId, Instant nextAttemptAt, String errorCode);

    /** True when the lease token is still held and unexpired. */
    boolean claimStillActive(UUID requestId, UUID claimToken, Instant asOf);

    /** Clears the worker lease after successful processing. Returns rows updated (0 if stale). */
    int releaseClaim(UUID requestId, UUID claimToken, Instant asOf);

    /**
     * Records a failed attempt, schedules the next retry, and clears the lease.
     * Requires a live lease token and refuses COMPLETE / non-pending durable rows.
     * Returns rows updated (0 if stale or expired).
     */
    int recordRetryScheduled(
            UUID requestId,
            UUID claimToken,
            Instant asOf,
            int newAttemptCount,
            Instant nextAttemptAt,
            String errorCode);

    /**
     * Marks COMPLETE only while the lease is held. Clears the lease and retry delay.
     * Returns rows updated (0 if stale/expired/already complete).
     */
    int markCompleteIfClaimed(UUID requestId, UUID claimToken, Instant asOf, Instant completedAt);

    /**
     * Marks the row durably recorded only when the lease token still matches.
     * Clears retry delay so completion reconciliation stays eligible.
     * Returns rows updated (0 if stale).
     */
    int markDurablyRecordedIfClaimed(UUID requestId, UUID claimToken, Instant asOf);
}
