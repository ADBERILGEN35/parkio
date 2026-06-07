package com.parkio.user.application.port;

import com.parkio.user.domain.UserTrustProfile;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for the {@link UserTrustProfile} projection. */
public interface UserTrustProfileRepository {

    UserTrustProfile save(UserTrustProfile trustProfile);

    Optional<UserTrustProfile> findByUserProfileId(UUID userProfileId);
}
