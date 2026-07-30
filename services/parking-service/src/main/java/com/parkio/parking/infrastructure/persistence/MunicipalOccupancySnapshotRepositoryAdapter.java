package com.parkio.parking.infrastructure.persistence;

import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MunicipalOccupancySnapshotRepositoryAdapter implements MunicipalOccupancySnapshotRepository {
    private final JdbcClient jdbc;

    public MunicipalOccupancySnapshotRepositoryAdapter(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean insertIfAbsent(UUID facilityId, UUID sourceId, UUID sourceLinkId,
            UUID syncRunId, NormalizedMunicipalOccupancy value) {
        Timestamp fetched = Timestamp.from(value.fetchedAt());
        Timestamp observed = value.sourceObservedAt() == null ? null : Timestamp.from(value.sourceObservedAt());
        int inserted = jdbc.sql("""
                INSERT INTO municipal_occupancy_snapshots
                    (id,facility_id,source_id,source_link_id,sync_run_id,source_observed_at,fetched_at,
                     timestamp_provenance,capacity_total,occupied_spaces,available_spaces,
                     occupancy_status,raw_record_hash,created_at)
                VALUES (:id,:facilityId,:sourceId,:linkId,:runId,:observed,:fetched,
                        :provenance,:capacity,:occupied,:available,:status,:hash,:fetched)
                ON CONFLICT ON CONSTRAINT uq_municipal_occupancy_snapshots_dedupe DO NOTHING
                """).param("id", UUID.randomUUID()).param("facilityId", facilityId).param("sourceId", sourceId)
                .param("linkId", sourceLinkId).param("runId", syncRunId)
                .param("observed", observed).param("fetched", fetched)
                .param("provenance", value.timestampProvenance().name()).param("capacity", value.capacityTotal())
                .param("occupied", value.occupiedSpaces()).param("available", value.availableSpaces())
                .param("status", value.occupancyStatus().name()).param("hash", value.rawRecordHash()).update();
        return inserted == 1;
    }

    @Override
    public Optional<Snapshot> latestForFacility(UUID facilityId) {
        return jdbc.sql("""
                SELECT capacity_total,occupied_spaces,available_spaces,fetched_at,
                       CASE WHEN source_observed_at IS NULL THEN NULL
                            ELSE GREATEST(0,EXTRACT(EPOCH FROM (fetched_at-source_observed_at)))::bigint END source_age_seconds,
                       occupancy_status <> 'INVALID' AS valid
                FROM municipal_occupancy_snapshots
                WHERE facility_id=:facilityId
                ORDER BY fetched_at DESC LIMIT 1
                """).param("facilityId", facilityId).query((rs, row) -> {
                    Timestamp fetched = rs.getTimestamp("fetched_at");
                    return new Snapshot((Integer) rs.getObject("capacity_total"),
                            (Integer) rs.getObject("occupied_spaces"), (Integer) rs.getObject("available_spaces"),
                            fetched == null ? null : fetched.toInstant(),
                            (Long) rs.getObject("source_age_seconds"), rs.getBoolean("valid"));
                }).optional();
    }

    @Override
    public long count() {
        return jdbc.sql("SELECT count(*) FROM municipal_occupancy_snapshots").query(Long.class).single();
    }
}