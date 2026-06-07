package com.parkio.user.application.port;

import com.parkio.user.domain.UserProfile;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link UserProfile}. */
public interface UserProfileRepository {

    UserProfile save(UserProfile profile);

    Optional<UserProfile> findByAuthUserId(UUID authUserId);

    boolean existsByAuthUserId(UUID authUserId);
}
