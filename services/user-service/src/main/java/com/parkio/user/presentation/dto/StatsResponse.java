package com.parkio.user.presentation.dto;

import com.parkio.user.domain.UserTrustProfile;

/** The caller's trust + gamification stats (projection). */
public record StatsResponse(int trustScore, String trustBand, long totalPoints, int currentLevel) {

    public static StatsResponse from(UserTrustProfile t) {
        return new StatsResponse(t.trustScore(), t.trustBand().name(), t.totalPoints(), t.currentLevel());
    }
}
