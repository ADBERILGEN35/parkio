package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.MunicipalSourceSyncRunRepository;
import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MunicipalSourceSyncRunRepositoryAdapter implements MunicipalSourceSyncRunRepository {
    private final JdbcClient jdbc;

    public MunicipalSourceSyncRunRepositoryAdapter(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<UUID> tryStart(UUID sourceId, String correlationId, Instant startedAt) {
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

    @Override
    public void complete(UUID runId, Instant completedAt, MunicipalSyncResult result,
            SchemaFingerprint fingerprint, String payloadHash) {
        jdbc.sql("""
                UPDATE municipal_source_sync_runs SET completed_at=:completed,status=:status,
                    records_received=:received,records_accepted=:accepted,records_rejected=:rejected,
                    records_inserted=:inserted,records_updated=:updated,records_unchanged=:unchanged,
                    occupancy_inserted=:occupancy,error_category=:category,error_summary=:summary,
                    schema_fingerprint=:fingerprint,payload_hash=:payloadHash
                WHERE id=:id
                """).param("completed", Timestamp.from(completedAt)).param("status", result.status().name())
                .param("received", result.recordsReceived()).param("accepted", result.recordsAccepted())
                .param("rejected", result.recordsRejected()).param("inserted", result.recordsInserted())
                .param("updated", result.recordsUpdated()).param("unchanged", result.recordsUnchanged())
                .param("occupancy", result.occupancyInserted()).param("category", result.errorCategory())
                .param("summary", result.errorSummary())
                .param("fingerprint", fingerprint == null ? null : fingerprint.value())
                .param("payloadHash", payloadHash).param("id", runId).update();
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
}