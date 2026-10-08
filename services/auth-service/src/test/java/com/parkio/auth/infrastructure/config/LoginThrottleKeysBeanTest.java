package com.parkio.auth.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.application.LoginThrottleKeys;
import com.parkio.auth.application.LoginThrottleTestKeys;
import com.parkio.auth.infrastructure.security.LoginThrottleProperties;
import org.junit.jupiter.api.Test;

/** The login throttle's secret is configured, or ephemeral in local development only; never absent (CL-F15 v3). */
class LoginThrottleKeysBeanTest {

    private final AuthInfrastructureConfig config = new AuthInfrastructureConfig();

    @Test
    void aConfiguredSecretIsUsedWithItsOptionalPrevious() {
        LoginThrottleProperties properties = new LoginThrottleProperties();
        properties.setHmacKey(LoginThrottleTestKeys.SECRET_B);
        properties.setHmacKeyPrevious(LoginThrottleTestKeys.SECRET_A);
        LoginThrottleKeys keys = config.loginThrottleKeys(properties);
        assertThat(keys.kid()).isEqualTo(LoginThrottleTestKeys.NEW_ONLY.kid());
        assertThat(keys.previousKid()).isEqualTo(LoginThrottleTestKeys.CURRENT.kid());
    }

    @Test
    void aMissingSecretRefusesToStartUnlessEphemeralGenerationIsOn() {
        LoginThrottleProperties properties = new LoginThrottleProperties();
        assertThatThrownBy(() -> config.loginThrottleKeys(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PARKIO_LOGIN_THROTTLE_HMAC_KEY");
        properties.setHmacKey("  ");
        assertThatThrownBy(() -> config.loginThrottleKeys(properties)).isInstanceOf(IllegalStateException.class);
        properties.setGenerateEphemeralHmacKey(true);
        assertThat(config.loginThrottleKeys(properties).hasPrevious()).isFalse();
    }

    @Test
    void ephemeralGenerationNeverReplacesAConfiguredButInvalidSecret() {
        LoginThrottleProperties properties = new LoginThrottleProperties();
        properties.setGenerateEphemeralHmacKey(true);
        properties.setHmacKey("too-short");
        assertThatThrownBy(() -> config.loginThrottleKeys(properties)).isInstanceOf(IllegalStateException.class);
    }
}
