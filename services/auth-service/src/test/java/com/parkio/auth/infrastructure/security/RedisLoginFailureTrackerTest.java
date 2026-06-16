package com.parkio.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisLoginFailureTrackerTest {

    private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedisLoginFailureTracker tracker;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> mockedValues = mock(ValueOperations.class);
        values = mockedValues;
        when(redis.opsForValue()).thenReturn(values);
        tracker = new RedisLoginFailureTracker(redis);
    }

    @Test
    void fifthFailureAppliesThirtySecondLockUsingRedisKeys() {
        when(values.increment("auth:login:failures:user@example.com")).thenReturn(5L);

        var outcome = tracker.recordFailure("user@example.com", NOW);

        assertThat(outcome.failureCount()).isEqualTo(5);
        assertThat(outcome.lockoutApplied()).isTrue();
        assertThat(outcome.lockedUntil()).isEqualTo(NOW.plusSeconds(30));
        verify(redis).expire("auth:login:failures:user@example.com", Duration.ofHours(24));
        verify(values).set("auth:login:lock:user@example.com", "5", Duration.ofSeconds(30));
    }

    @Test
    void twentiethFailureAppliesOneHourLock() {
        when(values.increment("auth:login:failures:user@example.com")).thenReturn(20L);

        var outcome = tracker.recordFailure("user@example.com", NOW);

        assertThat(outcome.lockoutApplied()).isTrue();
        assertThat(outcome.lockedUntil()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        verify(values).set("auth:login:lock:user@example.com", "20", Duration.ofHours(1));
    }

    @Test
    void resetDeletesAttemptAndLockKeys() {
        tracker.reset("user@example.com");

        verify(redis).delete("auth:login:failures:user@example.com");
        verify(redis).delete("auth:login:lock:user@example.com");
    }
}
