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
                """).param("facilityId", facilityId).query(this::mapSnapshot).optional();
    }

    @Override
    public Optional<Snapshot> latestForFacilityAndSourceKey(UUID facilityId, String sourceKey) {
        return jdbc.sql("""
                SELECT o.capacity_total,o.occupied_spaces,o.available_spaces,o.fetched_at,
                       CASE WHEN o.source_observed_at IS NULL THEN NULL
                            ELSE GREATEST(0,EXTRACT(EPOCH FROM (o.fetched_at-o.source_observed_at)))::bigint END source_age_seconds,
                       o.occupancy_status <> 'INVALID' AS valid
                FROM municipal_occupancy_snapshots o
                JOIN municipal_facility_source_links l ON l.id=o.source_link_id AND l.active=true
                JOIN municipal_data_sources s ON s.id=o.source_id
                WHERE o.facility_id=:facilityId AND s.source_key=:sourceKey AND s.active=true
                ORDER BY o.fetched_at DESC LIMIT 1
                """).param("facilityId", facilityId).param("sourceKey", sourceKey)
                .query(this::mapSnapshot).optional();
    }

    @Override
    public Optional<Snapshot> latestForSource(UUID sourceId) {
        return jdbc.sql("""
                SELECT capacity_total,occupied_spaces,available_spaces,fetched_at,
                       CASE WHEN source_observed_at IS NULL THEN NULL
                            ELSE GREATEST(0,EXTRACT(EPOCH FROM (fetched_at-source_observed_at)))::bigint END source_age_seconds,
                       occupancy_status <> 'INVALID' AS valid
                FROM municipal_occupancy_snapshots
                WHERE source_id=:sourceId
                ORDER BY fetched_at DESC LIMIT 1
                """).param("sourceId", sourceId).query(this::mapSnapshot).optional();
    }

    private Snapshot mapSnapshot(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp fetched = rs.getTimestamp("fetched_at");
        return new Snapshot(
                (Integer) rs.getObject("capacity_total"),
                (Integer) rs.getObject("occupied_spaces"),
                (Integer) rs.getObject("available_spaces"),
                fetched == null ? null : fetched.toInstant(),
                (Long) rs.getObject("source_age_seconds"),
                rs.getBoolean("valid"));
    }

    @Override
    public long count() {
        return jdbc.sql("SELECT count(*) FROM municipal_occupancy_snapshots").query(Long.class).single();
    }
}
