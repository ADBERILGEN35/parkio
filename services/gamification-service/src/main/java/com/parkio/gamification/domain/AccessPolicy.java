package com.parkio.gamification.domain;

import java.util.UUID;

/**
 * The access policy a user is granted, derived entirely from their current level's
 * {@link LevelRule}. Pure-domain value object.
 */
public record AccessPolicy(
        UUID userId,
        int currentLevel,
        int searchRadiusMeters,
        int resultLimit,
        int dailyViewLimit,
        boolean verifiedSpotPriority,
        boolean notificationPriority) {

    public static AccessPolicy from(UUID userId, LevelRule rule) {
        return new AccessPolicy(userId, rule.level(), rule.searchRadiusMeters(), rule.resultLimit(),
                rule.dailyViewLimit(), rule.verifiedSpotPriority(), rule.notificationPriority());
    }
}
