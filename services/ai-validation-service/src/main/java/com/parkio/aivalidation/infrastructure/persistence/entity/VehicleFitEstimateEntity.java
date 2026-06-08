package com.parkio.aivalidation.infrastructure.persistence.entity;

import com.parkio.aivalidation.domain.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code vehicle_fit_estimates} (child of a result). */
@Entity
@Table(name = "vehicle_fit_estimates")
public class VehicleFitEstimateEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "validation_result_id", nullable = false, updatable = false)
    private UUID validationResultId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, updatable = false)
    private VehicleType vehicleType;

    @Column(name = "fit_score", nullable = false, updatable = false)
    private int fitScore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VehicleFitEstimateEntity() {
        // for JPA
    }

    public VehicleFitEstimateEntity(UUID id, UUID validationResultId, VehicleType vehicleType,
                                    int fitScore, Instant createdAt) {
        this.id = id;
        this.validationResultId = validationResultId;
        this.vehicleType = vehicleType;
        this.fitScore = fitScore;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getValidationResultId() {
        return validationResultId;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public int getFitScore() {
        return fitScore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
