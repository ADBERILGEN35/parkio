package com.parkio.parking.application.port;

import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MunicipalSourceSyncRunRepository {
    record LatestRun(String status, String errorCategory, Instant completedAt) {}

    record CompletedRunView(
            String status,
            String errorCategory,
            Instant startedAt,
            Instant completedAt) {}

    /**
     * Stale RUNNING row terminalized by recovery. Control-state only — never mutates facilities.
     */
    record RecoveredRunView(UUID runId, UUID sourceId, String sourceKey, Instant startedAt) {}

    /**
     * Starts a RUNNING row. When stale-run recovery is enabled, any RUNNING row for
     * {@code sourceId} with {@code started_at < olderThan} is terminalized first so an
     * orphan lock cannot permanently block {@code tryStart}.
     */
    Optional<UUID> tryStart(UUID sourceId, String correlationId, Instant startedAt);

    /**
     * Completes a run only while it remains {@code RUNNING}. Returns {@code false} when
     * ownership was already lost (recovered/terminalized) so callers must not treat the
     * outcome as authoritative and must not call {@code markSuccessful}.
     */
    boolean complete(UUID runId, Instant completedAt, MunicipalSyncResult result,
                  SchemaFingerprint fingerprint, String payloadHash);

    /** True iff the run row exists and is still {@code RUNNING}. */
    boolean isRunning(UUID runId);

    /**
     * Atomically terminalizes all RUNNING rows with {@code started_at} strictly older than
     * {@code olderThan} to FAILED / {@code stale_run_recovered}. Provider-neutral.
     */
    List<RecoveredRunView> recoverStaleRunning(Instant olderThan, Instant completedAt);

    /**
     * Same as {@link #recoverStaleRunning(Instant, Instant)} scoped to one source
     * (used by self-healing {@link #tryStart}).
     */
    List<RecoveredRunView> recoverStaleRunningForSource(
            UUID sourceId, Instant olderThan, Instant completedAt);

    Optional<LatestRun> findLatestCompleted(UUID sourceId);

    /**
     * Newest-first completed runs (excludes RUNNING). Bounded by {@code limit} and backed by
     * {@code idx_municipal_source_sync_runs_source_started}.
     */
    List<CompletedRunView> findRecentCompleted(UUID sourceId, int limit);

    Optional<Instant> findLatestSuccessAt(UUID sourceId);

    int countFailuresSince(UUID sourceId, Instant sinceInclusive);

    /** RUNNING rows whose {@code started_at} is strictly older than {@code olderThan}. */
    int countStaleRunning(UUID sourceId, Instant olderThan);
}
