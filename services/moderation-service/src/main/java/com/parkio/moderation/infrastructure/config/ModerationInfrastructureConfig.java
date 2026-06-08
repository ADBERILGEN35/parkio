package com.parkio.moderation.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Infrastructure wiring. Exposes a system-UTC {@link Clock} so time-dependent logic
 * (case/report timestamps) is injectable and testable, and enables scheduling for the
 * outbox relay poller.
 */
@Configuration
@EnableScheduling
public class ModerationInfrastructureConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
