package com.parkio.gamification.application.port;

import com.parkio.gamification.domain.UserLevelProgress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link UserLevelProgress}. */
public interface UserLevelProgressRepository {

    UserLevelProgress save(UserLevelProgress progress);

    Optional<UserLevelProgress> findByUserId(UUID userId);

    /** Top users by total points, descending — the leaderboard. */
    List<UserLevelProgress> findTopByPoints(int limit);
}
