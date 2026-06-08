package com.parkio.gamification.domain;

/**
 * A level's point threshold and the access policy it grants. {@code maxPoints} is
 * {@code null} for the top, open-ended level. Pure domain value.
 */
public record LevelRule(
        int level,
        long minPoints,
        Long maxPoints,
        int searchRadiusMeters,
        int resultLimit,
        int dailyViewLimit,
        boolean verifiedSpotPriority,
        boolean notificationPriority) {

    /** Whether the given point total falls within this level's range. */
    public boolean matches(long points) {
        return points >= minPoints && (maxPoints == null || points <= maxPoints);
    }
}
