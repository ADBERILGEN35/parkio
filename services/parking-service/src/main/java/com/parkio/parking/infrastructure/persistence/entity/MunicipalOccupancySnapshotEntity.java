package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "municipal_occupancy_snapshots")
public class MunicipalOccupancySnapshotEntity {
    @Id private UUID id;
    @Column(name = "facility_id", nullable = false) private UUID facilityId;
    @Column(name = "source_id", nullable = false) private UUID sourceId;
    @Column(name = "source_link_id", nullable = false) private UUID sourceLinkId;
    @Column(name = "sync_run_id", nullable = false) private UUID syncRunId;
    @Column(name = "source_observed_at") private Instant sourceObservedAt;
    @Column(name = "fetched_at", nullable = false) private Instant fetchedAt;
    @Column(name = "timestamp_provenance", nullable = false) private String timestampProvenance;
    @Column(name = "capacity_total") private Integer capacityTotal;
    @Column(name = "occupied_spaces") private Integer occupiedSpaces;
    @Column(name = "available_spaces") private Integer availableSpaces;
    @Column(name = "occupancy_status", nullable = false) private String occupancyStatus;
    @Column(name = "raw_record_hash", nullable = false) private String rawRecordHash;
    protected MunicipalOccupancySnapshotEntity() {}
}
