package com.parkio.gamification.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code parkio.gamification.*}: leaderboard bounds. */
@ConfigurationProperties(prefix = "parkio.gamification")
public class GamificationProperties {

    private Leaderboard leaderboard = new Leaderboard();

    public Leaderboard getLeaderboard() {
        return leaderboard;
    }

    public void setLeaderboard(Leaderboard leaderboard) {
        this.leaderboard = leaderboard;
    }

    public static class Leaderboard {

        private int defaultLimit = 20;
        private int maxLimit = 100;

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public int getMaxLimit() {
            return maxLimit;
        }

        public void setMaxLimit(int maxLimit) {
            this.maxLimit = maxLimit;
        }
    }
}
