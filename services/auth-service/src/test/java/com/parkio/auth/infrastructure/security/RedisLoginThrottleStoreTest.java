package com.parkio.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * The Redis commands behind each throttle primitive (CL-F15 v2). The behaviour against a real Redis,
 * with TTL expiry and key types, is in {@code ThrottledLoginFailureTrackerRedisIT}.
 */
class RedisLoginThrottleStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SetOperations<String, String> sets;
    private ZSetOperations<String, String> zsets;
    private RedisLoginThrottleStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        sets = mock(SetOperations.class);
        zsets = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForZSet()).thenReturn(zsets);
        store = new RedisLoginThrottleStore(redis, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void incrementIsIncrThenExpireWithTheWindow() {
        when(values.increment("k")).thenReturn(3L);
        assertThat(store.increment("k", Duration.ofHours(24))).isEqualTo(3L);
        verify(redis).expire("k", Duration.ofHours(24));
    }

    @Test
    void holdIsASetWithTheDelayAsLifetime() {
        store.hold("w", Duration.ofSeconds(30));
        verify(values).set("w", "1", Duration.ofSeconds(30));
    }

    @Test
    void remainingReadsTheMillisecondLifetimeAndTreatsMissingOrEternalKeysAsNotWaiting() {
        when(redis.getExpire("w", TimeUnit.MILLISECONDS)).thenReturn(29_500L);
        assertThat(store.remaining("w")).isEqualTo(Duration.ofMillis(29_500));
        when(redis.getExpire("missing", TimeUnit.MILLISECONDS)).thenReturn(-2L);
        assertThat(store.remaining("missing")).isZero();
        when(redis.getExpire("eternal", TimeUnit.MILLISECONDS)).thenReturn(-1L);
        assertThat(store.remaining("eternal")).isZero();
        when(redis.getExpire("null", TimeUnit.MILLISECONDS)).thenReturn(null);
        assertThat(store.remaining("null")).isZero();
    }

    @Test
    void plainSetsAreSaddWithAKeyLifetime() {
        store.addToSet("s", "m", Duration.ofHours(24));
        verify(sets).add("s", "m");
        verify(redis).expire("s", Duration.ofHours(24));
        when(sets.members("s")).thenReturn(null);
        assertThat(store.setMembers("s")).isEmpty();
        when(sets.members("t")).thenReturn(Set.of("a"));
        assertThat(store.setMembers("t")).containsExactly("a");
    }

    @Test
    void timedSetsScoreMembersByExpiryAndPruneExpiredOnes() {
        long now = NOW.toEpochMilli();
        store.markTimed("z", "client", Duration.ofDays(30));
        verify(zsets).add("z", "client", (double) (now + Duration.ofDays(30).toMillis()));
        verify(zsets).removeRangeByScore("z", Double.NEGATIVE_INFINITY, (double) now);
        verify(redis).expire("z", Duration.ofDays(30));

        when(zsets.score("z", "fresh")).thenReturn((double) (now + 1));
        when(zsets.score("z", "expired")).thenReturn((double) now);
        assertThat(store.isTimedMember("z", "fresh")).isTrue();
        assertThat(store.isTimedMember("z", "expired")).isFalse();
        assertThat(store.isTimedMember("z", "absent")).isFalse();
    }

    @Test
    void deleteSendsOneCommandAndNothingForNoKeys() {
        store.delete(List.of("a", "b"));
        verify(redis).delete(List.of("a", "b"));
        store.delete(List.of());
        verify(redis, never()).delete(List.<String>of());
        verify(redis, never()).delete(any(String.class));
    }
}
