package com.parkio.gamification.presentation.dto;

import com.parkio.gamification.domain.UserLevelProgress;
import java.time.Instant;
import java.util.UUID;

/** A user's overall gamification progress. */
public record ProgressResponse(UUID userId, long totalPoints, int currentLevel, Instant updatedAt) {

    public static ProgressResponse from(UserLevelProgress p) {
        return new ProgressResponse(p.userId(), p.totalPoints(), p.currentLevel(), p.updatedAt());
    }
}
