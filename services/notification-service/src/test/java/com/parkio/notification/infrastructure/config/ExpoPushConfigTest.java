package com.parkio.notification.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** #595: the Expo client starts only with both timeouts bounded, and with connect shorter than read. */
class ExpoPushConfigTest {

    private static final String TOKEN = "synthetic-test-only";

    @Test
    void defaultsAndTheLimitsThemselvesAreAccepted() {
        assertThatCode(() -> config(Duration.ofSeconds(2), Duration.ofSeconds(5)).validate())
                .doesNotThrowAnyException();
        assertThatCode(() -> config(ExpoPushConfig.MAX_CONNECT_TIMEOUT, ExpoPushConfig.MAX_READ_TIMEOUT).validate())
                .doesNotThrowAnyException();
    }

    static Stream<Arguments> unboundedConnectTimeouts() {
        return Stream.of(
                Arguments.of((Duration) null),
                Arguments.of(Duration.ZERO),
                Arguments.of(Duration.ofMillis(-1)),
                Arguments.of(ExpoPushConfig.MAX_CONNECT_TIMEOUT.plusMillis(1)));
    }

    @ParameterizedTest
    @MethodSource("unboundedConnectTimeouts")
    void connectTimeoutMustBePositiveAndAtMostTheLimit(Duration connectTimeout) {
        assertThatThrownBy(() -> config(connectTimeout, ExpoPushConfig.MAX_READ_TIMEOUT).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PARKIO_EXPO_PUSH_CONNECT_TIMEOUT")
                .hasMessageContaining("at most PT30S");
    }

    static Stream<Arguments> unboundedReadTimeouts() {
        return Stream.of(
                Arguments.of((Duration) null),
                Arguments.of(Duration.ZERO),
                Arguments.of(Duration.ofMillis(-1)),
                Arguments.of(ExpoPushConfig.MAX_READ_TIMEOUT.plusMillis(1)));
    }

    @ParameterizedTest
    @MethodSource("unboundedReadTimeouts")
    void readTimeoutMustBePositiveAndAtMostTheLimit(Duration readTimeout) {
        assertThatThrownBy(() -> config(Duration.ofSeconds(2), readTimeout).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PARKIO_EXPO_PUSH_READ_TIMEOUT")
                .hasMessageContaining("at most PT1M");
    }

    @ParameterizedTest
    @MethodSource("connectNotShorterThanRead")
    void connectTimeoutMustBeShorterThanReadTimeout(Duration connectTimeout, Duration readTimeout) {
        assertThatThrownBy(() -> config(connectTimeout, readTimeout).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be shorter than PARKIO_EXPO_PUSH_READ_TIMEOUT");
    }

    static Stream<Arguments> connectNotShorterThanRead() {
        return Stream.of(
                Arguments.of(Duration.ofSeconds(5), Duration.ofSeconds(5)),
                Arguments.of(Duration.ofSeconds(10), Duration.ofSeconds(5)));
    }

    @Test
    void accessTokenIsStillRequired() {
        assertThatThrownBy(() -> new ExpoPushConfig("", Duration.ofSeconds(2), Duration.ofSeconds(5)).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PARKIO_EXPO_ACCESS_TOKEN");
    }

    private static ExpoPushConfig config(Duration connectTimeout, Duration readTimeout) {
        return new ExpoPushConfig(TOKEN, connectTimeout, readTimeout);
    }
}
