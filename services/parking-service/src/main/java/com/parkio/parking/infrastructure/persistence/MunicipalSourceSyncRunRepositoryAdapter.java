package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.externalsource.MunicipalSourceFailureCategory;
import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MunicipalSourceSyncRunRepositoryAdapter implements MunicipalSourceSyncRunRepository {
    private static final Logger log = LoggerFactory.getLogger(MunicipalSourceSyncRunRepositoryAdapter.class);
    private static final String STALE_CATEGORY =
            MunicipalSourceFailureCategory.STALE_RUN_RECOVERED.wireValue();
    private static final String STALE_SUMMARY =
            "MUNI-SYNC-RESILIENCE-01: stale RUNNING terminalized; sync-control only";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final MunicipalSourceProperties properties;

    public MunicipalSourceSyncRunRepositoryAdapter(
            JdbcClient jdbc, Clock clock, MunicipalSourceProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    @Transactional
    public Optional<UUID> tryStart(UUID sourceId, String correlationId, Instant startedAt) {
        maybeSelfHealStale(sourceId);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO municipal_source_sync_runs
                        (id,source_id,correlation_id,started_at,status,records_received,records_accepted,
                         records_rejected,records_inserted,records_updated,records_unchanged,occupancy_inserted)
                    VALUES (:id,:sourceId,:correlationId,:started,'RUNNING',0,0,0,0,0,0,0)
                    """).param("id", id).param("sourceId", sourceId)
                    .param("correlationId", correlationId)
                    .param("started", Timestamp.from(startedAt)).update();
            return Optional.of(id);
        } catch (DataIntegrityViolationException concurrentRun) {
            return Optional.empty();
        }
    }

    private void maybeSelfHealStale(UUID sourceId) {
        MunicipalSourceProperties.Sync sync = properties.getSync();
        if (!sync.isStaleRunRecoveryEnabled()) {
            return;
        }
        Instant completedAt = clock.instant();
        Instant olderThan = completedAt.minus(sync.getStaleRunningThreshold());
        List<RecoveredRunView> recovered = recoverStaleRunningForSource(sourceId, olderThan, completedAt);
        for (RecoveredRunView row : recovered) {
            log.info(
                    "municipal_sync_stale_recovered source={} ageBucket={} status=FAILED category={} via=tryStart",
                    row.sourceKey(),
                    ageBucket(row.startedAt(), completedAt),
                    STALE_CATEGORY);
        }
    }

    @Override
    public boolean complete(UUID runId, Instant completedAt, MunicipalSyncResult result,
            SchemaFingerprint fingerprint, String payloadHash) {
        int updated = jdbc.sql("""
                UPDATE municipal_source_sync_runs SET completed_at=:completed,status=:status,
                    records_received=:received,records_accepted=:accepted,records_rejected=:rejected,
                    records_inserted=:inserted,records_updated=:updated,records_unchanged=:unchanged,
                    occupancy_inserted=:occupancy,error_category=:category,error_summary=:summary,
                    schema_fingerprint=:fingerprint,payload_hash=:payloadHash
                WHERE id=:id AND status='RUNNING'
                """).param("completed", Timestamp.from(completedAt)).param("status", result.status().name())
                .param("received", result.recordsReceived()).param("accepted", result.recordsAccepted())
                .param("rejected", result.recordsRejected()).param("inserted", result.recordsInserted())
                .param("updated", result.recordsUpdated()).param("unchanged", result.recordsUnchanged())
                .param("occupancy", result.occupancyInserted()).param("category", result.errorCategory())
                .param("summary", result.errorSummary())
                .param("fingerprint", fingerprint == null ? null : fingerprint.value())
                .param("payloadHash", payloadHash).param("id", runId).update();
        return updated == 1;
    }

    @Override
    public boolean isRunning(UUID runId) {
        Integer count = jdbc.sql("""
                SELECT count(*)::int FROM municipal_source_sync_runs
                WHERE id = :id AND status = 'RUNNING'
                """).param("id", runId).query(Integer.class).single();
        return count != null && count == 1;
    }

    @Override
    public List<RecoveredRunView> recoverStaleRunning(Instant olderThan, Instant completedAt) {
        return jdbc.sql("""
                UPDATE municipal_source_sync_runs r
                SET status = 'FAILED',
                    completed_at = :completed,
                    error_category = :category,
                    error_summary = :summary
                FROM municipal_data_sources s
                WHERE r.source_id = s.id
                  AND r.status = 'RUNNING'
                  AND r.started_at < :olderThan
                RETURNING r.id, r.source_id, s.source_key, r.started_at
                """)
                .param("completed", Timestamp.from(completedAt))
                .param("category", STALE_CATEGORY)
                .param("summary", STALE_SUMMARY)
                .param("olderThan", Timestamp.from(olderThan))
                .query((rs, rowNum) -> new RecoveredRunView(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("source_id"),
                        rs.getString("source_key"),
                        rs.getTimestamp("started_at").toInstant()))
                .list();
    }

    @Override
    public List<RecoveredRunView> recoverStaleRunningForSource(
            UUID sourceId, Instant olderThan, Instant completedAt) {
        return jdbc.sql("""
                UPDATE municipal_source_sync_runs r
                SET status = 'FAILED',
                    completed_at = :completed,
                    error_category = :category,
                    error_summary = :summary
                FROM municipal_data_sources s
                WHERE r.source_id = s.id
                  AND r.source_id = :sourceId
                  AND r.status = 'RUNNING'
                  AND r.started_at < :olderThan
                RETURNING r.id, r.source_id, s.source_key, r.started_at
                """)
                .param("completed", Timestamp.from(completedAt))
                .param("category", STALE_CATEGORY)
                .param("summary", STALE_SUMMARY)
                .param("sourceId", sourceId)
                .param("olderThan", Timestamp.from(olderThan))
                .query((rs, rowNum) -> new RecoveredRunView(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("source_id"),
                        rs.getString("source_key"),
                        rs.getTimestamp("started_at").toInstant()))
                .list();
    }

    public static String ageBucket(Instant startedAt, Instant now) {
        long minutes = Math.max(0L, (now.getEpochSecond() - startedAt.getEpochSecond()) / 60L);
        if (minutes < 30) return "<30m";
        if (minutes < 60) return ">30m";
        if (minutes < 360) return ">1h";
        if (minutes < 1440) return ">6h";
        return ">24h";
    }

    @Override
    public Optional<LatestRun> findLatestCompleted(UUID sourceId) {
        return jdbc.sql("""
                SELECT status, error_category, completed_at
                FROM municipal_source_sync_runs
                WHERE source_id = :sourceId AND status <> 'RUNNING'
                ORDER BY started_at DESC
                LIMIT 1
                """).param("sourceId", sourceId).query((rs, row) -> {
            Timestamp completed = rs.getTimestamp("completed_at");
            return new LatestRun(
                    rs.getString("status"),
                    rs.getString("error_category"),
                    completed == null ? null : completed.toInstant());
        }).optional();
    }

    @Override
    public List<CompletedRunView> findRecentCompleted(UUID sourceId, int limit) {
        int bound = Math.max(1, Math.min(limit, 500));
        return jdbc.sql("""
                SELECT status, error_category, started_at, completed_at
                FROM municipal_source_sync_runs
                WHERE source_id = :sourceId AND status <> 'RUNNING'
                ORDER BY started_at DESC
                LIMIT :limit
                """).param("sourceId", sourceId).param("limit", bound)
                .query((rs, row) -> {
                    Timestamp started = rs.getTimestamp("started_at");
                    Timestamp completed = rs.getTimestamp("completed_at");
                    return new CompletedRunView(
                            rs.getString("status"),
                            rs.getString("error_category"),
                            started == null ? null : started.toInstant(),
                            completed == null ? null : completed.toInstant());
                }).list();
    }

    @Override
    public Optional<Instant> findLatestSuccessAt(UUID sourceId) {
        return jdbc.sql("""
                SELECT completed_at
                FROM municipal_source_sync_runs
                WHERE source_id = :sourceId
                  AND status IN ('SUCCESS', 'PARTIAL_SUCCESS')
                  AND completed_at IS NOT NULL
                ORDER BY completed_at DESC
                LIMIT 1
                """).param("sourceId", sourceId).query((rs, row) -> {
            Timestamp completed = rs.getTimestamp("completed_at");
            return completed == null ? null : completed.toInstant();
        }).optional();
    }

    @Override
    public int countFailuresSince(UUID sourceId, Instant sinceInclusive) {
        Integer count = jdbc.sql("""
                SELECT count(*)::int
                FROM municipal_source_sync_runs
                WHERE source_id = :sourceId
                  AND status = 'FAILED'
                  AND started_at >= :since
                """).param("sourceId", sourceId)
                .param("since", Timestamp.from(sinceInclusive))
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    @Override
    public int countStaleRunning(UUID sourceId, Instant olderThan) {
        Integer count = jdbc.sql("""
                SELECT count(*)::int
                FROM municipal_source_sync_runs
                WHERE source_id = :sourceId
                  AND status = 'RUNNING'
                  AND started_at < :olderThan
                """).param("sourceId", sourceId)
                .param("olderThan", Timestamp.from(olderThan))
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }
}
