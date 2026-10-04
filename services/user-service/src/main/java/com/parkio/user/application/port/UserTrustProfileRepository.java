package com.parkio.user.application.port;

import com.parkio.user.domain.TrustBand;
import com.parkio.user.domain.UserTrustProfile;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for the {@link UserTrustProfile} projection. */
public interface UserTrustProfileRepository {

    UserTrustProfile save(UserTrustProfile trustProfile);

    Optional<UserTrustProfile> findByUserProfileId(UUID userProfileId);

    /*
     * Gamification snapshots, applied in version order (U12, CX-F07). Each method is one
     * conditional UPDATE. A versioned value applies only when it is newer than the version
     * projected for that value; a version-less (pre-U12) value applies only while no versioned
     * one has been projected. Concurrent deliveries therefore keep the highest version, and a
     * late, duplicate or redriven snapshot never regresses the projection. Each method returns
     * whether the value was applied.
     */

    boolean projectTotalPoints(UUID userProfileId, long totalPoints, Long aggregateVersion);

    boolean projectLevel(UUID userProfileId, int currentLevel, Long aggregateVersion);

    boolean projectTrustScore(UUID userProfileId, int trustScore, TrustBand trustBand, Long aggregateVersion);
}
