package com.parkio.gamification.application;

/**
 * Tunable leaderboard bounds, supplied by infrastructure from
 * {@code parkio.gamification.leaderboard.*}. Plain value — keeps the application
 * layer free of Spring config types.
 */
public record LeaderboardSettings(int defaultLimit, int maxLimit) {
}
