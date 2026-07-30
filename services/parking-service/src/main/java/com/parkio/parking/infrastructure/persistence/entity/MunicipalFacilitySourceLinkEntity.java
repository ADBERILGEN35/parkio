package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "municipal_facility_source_links")
public class MunicipalFacilitySourceLinkEntity {
    @Id private UUID id;
    @Column(name = "facility_id", nullable = false) private UUID facilityId;
    @Column(name = "source_id", nullable = false) private UUID sourceId;
    @Column(name = "external_id", nullable = false) private String externalId;
    @Column(name = "raw_record_hash", nullable = false) private String rawRecordHash;
    @Column(name = "first_seen_at", nullable = false) private Instant firstSeenAt;
    @Column(name = "last_seen_at", nullable = false) private Instant lastSeenAt;
    @Column(nullable = false) private boolean active;
    protected MunicipalFacilitySourceLinkEntity() {}
}
