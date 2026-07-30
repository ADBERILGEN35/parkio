package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "municipal_parking_facilities")
public class MunicipalParkingFacilityEntity {
    @Id private UUID id;
    @Column(name = "operator_name") private String operatorName;
    @Column(name = "facility_type", nullable = false) private String facilityType;
    @Column(name = "display_name", nullable = false) private String displayName;
    @Column(name = "address_text") private String addressText;
    @Column(nullable = false) private double latitude;
    @Column(nullable = false) private double longitude;
    @Column(name = "capacity_total") private Integer capacityTotal;
    @Column(name = "is_paid") private Boolean paid;
    private Boolean nonstop;
    @Column(nullable = false) private boolean active;
    @Column(name = "access_classification", nullable = false) private String accessClassification;
    @Column(name = "primary_source_key") private String primarySourceKey;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;
    protected MunicipalParkingFacilityEntity() {}
}