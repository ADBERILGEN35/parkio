package com.parkio.gateway.infrastructure.config;

import com.parkio.gateway.infrastructure.client.SessionEpochProperties;
import com.parkio.gateway.infrastructure.client.UserStatusProperties;
import com.parkio.gateway.infrastructure.security.JwtProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the gateway's configuration properties and shared infrastructure
 * beans. Keeping the {@link Clock} injectable keeps time deterministic in tests.
 */
@Configuration
@EnableConfigurationProperties({
    JwtProperties.class,
    CorsProperties.class,
    UserStatusProperties.class,
    SessionEpochProperties.class,
    TrustedProxyProperties.class
})
public class GatewayConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
