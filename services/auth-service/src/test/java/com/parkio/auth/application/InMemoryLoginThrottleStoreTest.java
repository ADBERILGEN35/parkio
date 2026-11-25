package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The in-memory store keeps the port's contract where the throttle tests rely on it: like the Redis
 * store, it refuses a counter or set lifetime under 1 ms, so a caller that passes one fails in the
 * tests too (#606).
 */
class InMemoryLoginThrottleStoreTest {

    private final InMemoryLoginThrottleStore store = new InMemoryLoginThrottleStore(
            Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void aLifetimeUnderOneMillisecondIsRefusedAsTheRedisStoreRefusesIt() {
        for (Duration lifetime : List.of(Duration.ZERO, Duration.ofNanos(999_999), Duration.ofMillis(-1))) {
            assertThatThrownBy(() -> store.increment("k", lifetime)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.addToSet("s", "m", lifetime)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(store.keys()).isEmpty();

        assertThat(store.increment("k", Duration.ofMillis(1))).isEqualTo(1L);
        store.addToSet("s", "m", Duration.ofMillis(1));
        assertThat(store.keys()).containsExactly("k", "s");
    }
}
