package com.parkio.user.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A user's optional vehicle profile (1:1 with a {@link UserProfile}). Both the
 * type and plate are optional. The {@code plate} is private — it must never be
 * exposed in a public profile.
 */
public final class UserVehicleProfile {

    private final UUID id;
    private final UUID userProfileId;
    private VehicleType vehicleType;
    private String plate;
    private final Long version;

    public UserVehicleProfile(UUID id,
                              UUID userProfileId,
                              VehicleType vehicleType,
                              String plate,
                              Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userProfileId = Objects.requireNonNull(userProfileId, "userProfileId");
        this.vehicleType = vehicleType;
        this.plate = normalizePlate(plate);
        this.version = version;
    }

    /** Creates a vehicle profile (used by the PUT upsert when none exists yet). */
    public static UserVehicleProfile create(UUID userProfileId, VehicleType vehicleType, String plate) {
        return new UserVehicleProfile(UUID.randomUUID(), userProfileId, vehicleType, plate, null);
    }

    /** Replaces the vehicle details (PUT semantics — both fields are set as given). */
    public void replace(VehicleType vehicleType, String plate) {
        this.vehicleType = vehicleType;
        this.plate = normalizePlate(plate);
    }

    private static String normalizePlate(String plate) {
        if (plate == null) {
            return null;
        }
        String trimmed = plate.trim().toUpperCase();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public UUID id() {
        return id;
    }

    public UUID userProfileId() {
        return userProfileId;
    }

    public VehicleType vehicleType() {
        return vehicleType;
    }

    public String plate() {
        return plate;
    }

    public Long version() {
        return version;
    }
}
