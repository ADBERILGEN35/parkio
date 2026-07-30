package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "municipal_source_sync_runs")
public class MunicipalSourceSyncRunEntity {
    @Id private UUID id;
    @Column(name = "source_id", nullable = false) private UUID sourceId;
    @Column(name = "correlation_id", nullable = false) private String correlationId;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(nullable = false) private String status;
    protected MunicipalSourceSyncRunEntity() {}
}
