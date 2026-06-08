package com.parkio.gamification.presentation.dto;

import com.parkio.gamification.domain.UserLevelProgress;
import java.util.UUID;

/** One ranked leaderboard row. */
public record LeaderboardEntryResponse(int rank, UUID userId, long totalPoints, int currentLevel) {

    public static LeaderboardEntryResponse from(int rank, UserLevelProgress p) {
        return new LeaderboardEntryResponse(rank, p.userId(), p.totalPoints(), p.currentLevel());
    }
}
