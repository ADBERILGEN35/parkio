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

    Optional<UUID> tryStart(UUID sourceId, String correlationId, Instant startedAt);
    void complete(UUID runId, Instant completedAt, MunicipalSyncResult result,
                  SchemaFingerprint fingerprint, String payloadHash);
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