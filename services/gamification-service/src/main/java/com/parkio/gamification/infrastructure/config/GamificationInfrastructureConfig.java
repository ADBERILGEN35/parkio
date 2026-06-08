package com.parkio.gamification.infrastructure.config;

import com.parkio.gamification.application.LeaderboardSettings;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Infrastructure wiring: a system-UTC {@link Clock} and the application's
 * {@link LeaderboardSettings} derived from properties (so the application layer
 * stays free of Spring config types).
 */
@Configuration
@EnableConfigurationProperties(GamificationProperties.class)
public class GamificationInfrastructureConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public LeaderboardSettings leaderboardSettings(GamificationProperties properties) {
        return new LeaderboardSettings(
                properties.getLeaderboard().getDefaultLimit(),
                properties.getLeaderboard().getMaxLimit());
    }
}
