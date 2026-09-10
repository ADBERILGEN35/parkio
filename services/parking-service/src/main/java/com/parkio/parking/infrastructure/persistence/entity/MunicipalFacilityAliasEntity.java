package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "municipal_facility_aliases")
public class MunicipalFacilityAliasEntity {
    @Id
    @Column(name = "from_facility_id")
    private UUID fromFacilityId;
    @Column(name = "to_facility_id", nullable = false) private UUID toFacilityId;
    @Column(name = "candidate_id") private UUID candidateId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "created_by", nullable = false) private String createdBy;

    protected MunicipalFacilityAliasEntity() {}

    public MunicipalFacilityAliasEntity(
            UUID fromFacilityId,
            UUID toFacilityId,
            UUID candidateId,
            Instant createdAt,
            String createdBy) {
        this.fromFacilityId = fromFacilityId;
        this.toFacilityId = toFacilityId;
        this.candidateId = candidateId;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }
}
