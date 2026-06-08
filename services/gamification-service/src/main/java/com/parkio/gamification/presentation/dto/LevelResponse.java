package com.parkio.gamification.presentation.dto;

import com.parkio.gamification.application.result.LevelView;
import java.util.UUID;

/** A user's level standing and progress toward the next level. */
public record LevelResponse(
        UUID userId,
        int currentLevel,
        long totalPoints,
        long currentLevelMinPoints,
        Long nextLevelMinPoints,
        Long pointsToNextLevel) {

    public static LevelResponse from(LevelView v) {
        Long pointsToNext = v.nextLevelMinPoints() == null
                ? null
                : Math.max(0, v.nextLevelMinPoints() - v.totalPoints());
        return new LevelResponse(v.userId(), v.currentLevel(), v.totalPoints(),
                v.currentLevelMinPoints(), v.nextLevelMinPoints(), pointsToNext);
    }
}
