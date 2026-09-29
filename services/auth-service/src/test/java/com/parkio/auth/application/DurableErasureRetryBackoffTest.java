package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DurableErasureRetryBackoffTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    @Test
    void backoffIsExponentialAndCapped() {
        Duration base = Duration.ofSeconds(5);
        Duration max = Duration.ofMinutes(2);
        Instant t1 = DurableErasureRetryBackoff.nextAttemptAfter(NOW, 1, base, max);
        Instant t2 = DurableErasureRetryBackoff.nextAttemptAfter(NOW, 2, base, max);
        Instant t3 = DurableErasureRetryBackoff.nextAttemptAfter(NOW, 3, base, max);
        Instant t10 = DurableErasureRetryBackoff.nextAttemptAfter(NOW, 10, base, max);

        assertThat(t1).isEqualTo(NOW.plusSeconds(5));
        assertThat(t2).isEqualTo(NOW.plusSeconds(10));
        assertThat(t3).isEqualTo(NOW.plusSeconds(20));
        assertThat(t10).isEqualTo(NOW.plus(max));
    }
}
