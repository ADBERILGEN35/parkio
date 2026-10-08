package com.parkio.auth.infrastructure.config;

import com.parkio.auth.application.LoginThrottleKeys;
import com.parkio.auth.infrastructure.security.JwtProperties;
import com.parkio.auth.infrastructure.security.LoginThrottleProperties;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;

/**
 * Infrastructure wiring: enables {@link JwtProperties}, {@link RegistrationProperties} and
 * {@link LoginThrottleProperties} binding, scheduling (for the outbox relay's poller) and exposes a
 * system-UTC {@link Clock} so time-dependent logic (token expiry) is injectable and testable.
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, RegistrationProperties.class, LoginThrottleProperties.class})
@EnableScheduling
public class AuthInfrastructureConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthInfrastructureConfig.class);

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The login throttle's keyed-hashing secret (CL-F15 v3). Like the JWT signing key: configured
     * everywhere but local development, where {@code generate-ephemeral-hmac-key} (dev profile only)
     * substitutes a per-process random key. No fallback to another secret.
     */
    @Bean
    public LoginThrottleKeys loginThrottleKeys(LoginThrottleProperties properties) {
        if (StringUtils.hasText(properties.getHmacKey())) {
            return LoginThrottleKeys.of(properties.getHmacKey(), properties.getHmacKeyPrevious());
        }
        if (properties.isGenerateEphemeralHmacKey()) {
            log.warn("PARKIO_LOGIN_THROTTLE_HMAC_KEY is not set; using an ephemeral login-throttle key "
                    + "(local development only: throttle state does not survive a restart)");
            return LoginThrottleKeys.ephemeral();
        }
        throw new IllegalStateException("parkio.security.login-throttle.hmac-key (PARKIO_LOGIN_THROTTLE_HMAC_KEY) "
                + "must be configured: at least " + LoginThrottleKeys.MIN_LENGTH
                + " characters, generated for this purpose and managed separately from every other secret");
    }
}
