package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The keyed digests of the login throttle's key space (CL-F15 v3). */
class LoginThrottleKeysTest {

    private static final String SECRET = LoginThrottleTestKeys.SECRET_A;
    private static final String OTHER = LoginThrottleTestKeys.SECRET_B;

    @Test
    void digestsAreKeyedStableAndTruncatedHmacs() {
        LoginThrottleKeys keys = LoginThrottleKeys.of(SECRET, null);
        assertThat(keys.digest("owner@example.com")).hasSize(32).matches("[0-9a-f]+")
                .isEqualTo(LoginThrottleKeys.of(SECRET, null).digest("owner@example.com"))
                .isNotEqualTo(LoginThrottleKeys.of(OTHER, null).digest("owner@example.com"))
                .isNotEqualTo(keys.digest("other@example.com"));
        assertThat(keys.digest(null)).isEqualTo(keys.digest(""));
        assertThat(keys.kid()).hasSize(8).matches("[0-9a-f]+")
                .isEqualTo(LoginThrottleKeys.of(SECRET, null).kid())
                .isNotEqualTo(LoginThrottleKeys.of(OTHER, null).kid());
        assertThat(keys.hasPrevious()).isFalse();
        assertThat(keys.previousKid()).isNull();
        assertThat(keys.previousDigest("owner@example.com")).isNull();
    }

    @Test
    void aRotationExposesThePreviousSecretsDigestsAndKeyId() {
        LoginThrottleKeys rotated = LoginThrottleKeys.of(OTHER, SECRET);
        LoginThrottleKeys old = LoginThrottleKeys.of(SECRET, null);
        assertThat(rotated.hasPrevious()).isTrue();
        assertThat(rotated.kid()).isEqualTo(LoginThrottleKeys.of(OTHER, null).kid());
        assertThat(rotated.previousKid()).isEqualTo(old.kid());
        assertThat(rotated.previousDigest("owner@example.com")).isEqualTo(old.digest("owner@example.com"));
        assertThat(rotated.digest("owner@example.com")).isNotEqualTo(old.digest("owner@example.com"));
    }

    @Test
    void refusesShortMissingOrRepeatedSecrets() {
        assertThatThrownBy(() -> LoginThrottleKeys.of(null, null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
        assertThatThrownBy(() -> LoginThrottleKeys.of("   ", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LoginThrottleKeys.of("short-secret", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LoginThrottleKeys.of(SECRET, "short-previous")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("previous");
        assertThatThrownBy(() -> LoginThrottleKeys.of(SECRET, SECRET)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differ");
        assertThat(LoginThrottleKeys.of(SECRET, "  ").hasPrevious()).isFalse();
    }

    @Test
    void ephemeralKeysAreRandomPerProcess() {
        LoginThrottleKeys a = LoginThrottleKeys.ephemeral();
        LoginThrottleKeys b = LoginThrottleKeys.ephemeral();
        assertThat(a.kid()).isNotEqualTo(b.kid());
        assertThat(a.digest("owner@example.com")).isNotEqualTo(b.digest("owner@example.com"));
        assertThat(a.hasPrevious()).isFalse();
    }

    @Test
    void theOverlapCoversEveryEntryWrittenUnderThePreviousSecret() {
        assertThat(LoginThrottlePolicy.HMAC_KEY_OVERLAP)
                .isGreaterThanOrEqualTo(LoginThrottlePolicy.KNOWN_CLIENT_TTL)
                .isGreaterThanOrEqualTo(LoginThrottlePolicy.PAIR_WINDOW)
                .isGreaterThanOrEqualTo(LoginThrottlePolicy.ACCOUNT_WINDOW);
        assertThat(LoginThrottlePolicy.HMAC_KEY_ROTATION_INTERVAL).isGreaterThan(LoginThrottlePolicy.HMAC_KEY_OVERLAP);
    }
}
