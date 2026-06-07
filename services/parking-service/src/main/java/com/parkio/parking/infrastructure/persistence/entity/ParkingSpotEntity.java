package com.parkio.parking.infrastructure.persistence.entity;

import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSpotStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping for {@code parking_spots}. A persistence detail, not the domain.
 *
 * <p>The {@code location} geography column is intentionally not mapped here — it is
 * maintained by a DB trigger from {@code latitude}/{@code longitude} and used only
 * by the PostGIS radius query. Vehicle-type and violation-reason sets are stored as
 * comma-separated strings (the mapper splits/joins) to avoid extra tables.
 */
@Entity
@Table(name = "parking_spots")
public class ParkingSpotEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Column(name = "media_id", nullable = false, updatable = false)
    private UUID mediaId;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "address_text")
    private String addressText;

    @Column(name = "description")
    private String description;

    @Column(name = "manual_location_edited", nullable = false)
    private boolean manualLocationEdited;

    @Column(name = "suitable_vehicle_types", nullable = false)
    private String suitableVehicleTypes;

    @Enumerated(EnumType.STRING)
    @Column(name = "parking_context", nullable = false)
    private ParkingContext parkingContext;

    @Enumerated(EnumType.STRING)
    @Column(name = "legal_status", nullable = false)
    private LegalStatus legalStatus;

    @Column(name = "violation_reasons")
    private String violationReasons;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ParkingSpotStatus status;

    @Column(name = "confidence_score", nullable = false)
    private double confidenceScore;

    @Column(name = "verification_count", nullable = false)
    private int verificationCount;

    @Column(name = "filled_report_count", nullable = false)
    private int filledReportCount;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ParkingSpotEntity() {
        // for JPA
    }

    public ParkingSpotEntity(UUID id, UUID ownerUserId, UUID mediaId, double latitude, double longitude,
                             String addressText, String description, boolean manualLocationEdited,
                             String suitableVehicleTypes, ParkingContext parkingContext, LegalStatus legalStatus,
                             String violationReasons, ParkingSpotStatus status, double confidenceScore,
                             int verificationCount, int filledReportCount, Instant expiresAt,
                             Instant createdAt, Instant updatedAt, Long version) {
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.mediaId = mediaId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.addressText = addressText;
        this.description = description;
        this.manualLocationEdited = manualLocationEdited;
        this.suitableVehicleTypes = suitableVehicleTypes;
        this.parkingContext = parkingContext;
        this.legalStatus = legalStatus;
        this.violationReasons = violationReasons;
        this.status = status;
        this.confidenceScore = confidenceScore;
        this.verificationCount = verificationCount;
        this.filledReportCount = filledReportCount;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public UUID getMediaId() {
        return mediaId;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public String getAddressText() {
        return addressText;
    }

    public String getDescription() {
        return description;
    }

    public boolean isManualLocationEdited() {
        return manualLocationEdited;
    }

    public String getSuitableVehicleTypes() {
        return suitableVehicleTypes;
    }

    public ParkingContext getParkingContext() {
        return parkingContext;
    }

    public LegalStatus getLegalStatus() {
        return legalStatus;
    }

    public String getViolationReasons() {
        return violationReasons;
    }

    public ParkingSpotStatus getStatus() {
        return status;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public int getVerificationCount() {
        return verificationCount;
    }

    public int getFilledReportCount() {
        return filledReportCount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
