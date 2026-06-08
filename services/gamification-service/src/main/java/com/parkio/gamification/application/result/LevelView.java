package com.parkio.gamification.application.result;

import java.util.UUID;

/**
 * A user's level standing: current level, points, the current level's floor, and
 * the points at which the next level begins ({@code null} at the top level).
 */
public record LevelView(
        UUID userId,
        int currentLevel,
        long totalPoints,
        long currentLevelMinPoints,
        Long nextLevelMinPoints) {
}
