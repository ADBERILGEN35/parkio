package com.parkio.auth.infrastructure.config;

import com.parkio.auth.infrastructure.security.JwtProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Infrastructure wiring: enables {@link JwtProperties} binding, scheduling (for the
 * outbox relay's poller) and exposes a system-UTC {@link Clock} so time-dependent
 * logic (token expiry) is injectable and testable.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
@EnableScheduling
public class AuthInfrastructureConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
