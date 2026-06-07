package com.parkio.user.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Infrastructure wiring. Exposes a system-UTC {@link Clock} so time-dependent
 * logic (created/occurred timestamps) is injectable and testable.
 */
@Configuration
public class UserInfrastructureConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
