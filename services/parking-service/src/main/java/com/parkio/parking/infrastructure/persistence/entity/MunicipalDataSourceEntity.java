package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "municipal_data_sources")
public class MunicipalDataSourceEntity {
    @Id private UUID id;
    @Column(name = "source_key", nullable = false) private String sourceKey;
    @Column(nullable = false) private String publisher;
    @Column(name = "attribution_text", nullable = false) private String attributionText;
    @Column(name = "aging_after_seconds", nullable = false) private int agingAfterSeconds;
    @Column(name = "stale_after_seconds", nullable = false) private int staleAfterSeconds;
    @Column(name = "last_successful_sync_at") private Instant lastSuccessfulSyncAt;
    @Column(nullable = false) private boolean active;
    protected MunicipalDataSourceEntity() {}
}