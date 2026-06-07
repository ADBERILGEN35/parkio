package com.parkio.user.infrastructure.persistence.entity;

import com.parkio.user.domain.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/** JPA mapping for {@code user_vehicle_profiles}. */
@Entity
@Table(name = "user_vehicle_profiles")
public class UserVehicleProfileEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_profile_id", nullable = false, unique = true, updatable = false)
    private UUID userProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type")
    private VehicleType vehicleType;

    @Column(name = "plate")
    private String plate;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected UserVehicleProfileEntity() {
        // for JPA
    }

    public UserVehicleProfileEntity(UUID id,
                                    UUID userProfileId,
                                    VehicleType vehicleType,
                                    String plate,
                                    Long version) {
        this.id = id;
        this.userProfileId = userProfileId;
        this.vehicleType = vehicleType;
        this.plate = plate;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserProfileId() {
        return userProfileId;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public String getPlate() {
        return plate;
    }

    public Long getVersion() {
        return version;
    }
}
