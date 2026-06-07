package com.parkio.user.application.port;

import com.parkio.user.domain.UserVehicleProfile;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link UserVehicleProfile}. */
public interface UserVehicleProfileRepository {

    UserVehicleProfile save(UserVehicleProfile vehicle);

    Optional<UserVehicleProfile> findByUserProfileId(UUID userProfileId);
}
