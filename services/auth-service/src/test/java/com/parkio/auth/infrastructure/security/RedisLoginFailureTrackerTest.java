package com.parkio.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.auth.application.LoginThrottlePolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Key layout and tier wiring of the Redis tracker (CL-F15). The behaviour against a real
 * Redis, including TTL expiry, is in {@code RedisLoginFailureTrackerRedisIT}.
 */
class RedisLoginFailureTrackerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final String EMAIL = "user@example.com";
    private static final String CLIENT = "198.51.100.7";
    private static final String E = RedisLoginFailureTracker.digest(EMAIL);
    private static final String C = RedisLoginFailureTracker.digest(CLIENT);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SetOperations<String, String> sets;
    private RedisLoginFailureTracker tracker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        tracker = new RedisLoginFailureTracker(redis);
    }

    @Test
    void keysCarryDigestsNotTheRawEmailOrAddress() {
        assertThat(RedisLoginFailureTracker.pairFailuresKey(E, C))
                .isEqualTo("auth:login:v2:pair:" + E + ":" + C + ":failures")
                .doesNotContain(EMAIL).doesNotContain(CLIENT);
        assertThat(E).hasSize(32).matches("[0-9a-f]+");
        assertThat(RedisLoginFailureTracker.digest(EMAIL)).isEqualTo(E);
        assertThat(RedisLoginFailureTracker.digest("other@example.com")).isNotEqualTo(E);
    }

    @Test
    void fifthFailureOfAPairSetsAThirtySecondWaitForThatPairOnly() {
        when(values.increment(RedisLoginFailureTracker.pairFailuresKey(E, C))).thenReturn(5L);
        when(values.increment(RedisLoginFailureTracker.accountFailuresKey(E))).thenReturn(5L);

        var outcome = tracker.recordFailure(EMAIL, CLIENT, NOW);

        assertThat(outcome.pairFailures()).isEqualTo(5);
        assertThat(outcome.accountFailures()).isEqualTo(5);
        assertThat(outcome.throttled()).isTrue();
        assertThat(outcome.delay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(outcome.retryAt()).isEqualTo(NOW.plusSeconds(30));
        verify(redis).expire(RedisLoginFailureTracker.pairFailuresKey(E, C), LoginThrottlePolicy.PAIR_WINDOW);
        verify(redis).expire(RedisLoginFailureTracker.accountFailuresKey(E), LoginThrottlePolicy.ACCOUNT_WINDOW);
        verify(sets).add(RedisLoginFailureTracker.clientsKey(E), C);
        verify(values).set(RedisLoginFailureTracker.pairWaitKey(E, C), "5", Duration.ofSeconds(30));
        verify(values, never()).set(RedisLoginFailureTracker.accountWaitKey(E), "5", LoginThrottlePolicy.ACCOUNT_SOFT_DELAY);
    }

    @Test
    void reachingTheAccountCapSetsTheSoftDelayEvenForAFreshClient() {
        when(values.increment(RedisLoginFailureTracker.pairFailuresKey(E, C))).thenReturn(1L);
        when(values.increment(RedisLoginFailureTracker.accountFailuresKey(E)))
                .thenReturn(LoginThrottlePolicy.ACCOUNT_SOFT_CAP);

        var outcome = tracker.recordFailure(EMAIL, CLIENT, NOW);

        assertThat(outcome.delay()).isEqualTo(LoginThrottlePolicy.ACCOUNT_SOFT_DELAY);
        verify(values).set(RedisLoginFailureTracker.accountWaitKey(E),
                Long.toString(LoginThrottlePolicy.ACCOUNT_SOFT_CAP), LoginThrottlePolicy.ACCOUNT_SOFT_DELAY);
        verify(values, never()).set(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(30)));
    }

    @Test
    void retryAfterIsTheLongerOfThePairAndAccountWaits() {
        when(redis.getExpire(RedisLoginFailureTracker.pairWaitKey(E, C), TimeUnit.SECONDS)).thenReturn(12L);
        when(redis.getExpire(RedisLoginFailureTracker.accountWaitKey(E), TimeUnit.SECONDS)).thenReturn(-2L);
        assertThat(tracker.retryAfter(EMAIL, CLIENT, NOW)).isEqualTo(Duration.ofSeconds(12));

        when(redis.getExpire(RedisLoginFailureTracker.pairWaitKey(E, C), TimeUnit.SECONDS)).thenReturn(-2L);
        when(redis.getExpire(RedisLoginFailureTracker.accountWaitKey(E), TimeUnit.SECONDS)).thenReturn(7L);
        assertThat(tracker.retryAfter(EMAIL, CLIENT, NOW)).isEqualTo(Duration.ofSeconds(7));

        when(redis.getExpire(RedisLoginFailureTracker.accountWaitKey(E), TimeUnit.SECONDS)).thenReturn(-2L);
        assertThat(tracker.retryAfter(EMAIL, CLIENT, NOW)).isEqualTo(Duration.ZERO);
    }

    @Test
    void successClearsThePairAndTheAccountCounters() {
        tracker.clearAfterSuccess(EMAIL, CLIENT);

        verify(redis).delete(RedisLoginFailureTracker.pairFailuresKey(E, C));
        verify(redis).delete(RedisLoginFailureTracker.pairWaitKey(E, C));
        verify(sets).remove(RedisLoginFailureTracker.clientsKey(E), C);
        verify(redis).delete(RedisLoginFailureTracker.accountFailuresKey(E));
        verify(redis).delete(RedisLoginFailureTracker.accountWaitKey(E));
    }

    @Test
    void accountClearRemovesEveryKnownClientPair() {
        String other = RedisLoginFailureTracker.digest("203.0.113.9");
        when(sets.members(RedisLoginFailureTracker.clientsKey(E))).thenReturn(Set.of(C, other));

        tracker.clearAccount(EMAIL);

        verify(redis).delete(RedisLoginFailureTracker.pairFailuresKey(E, C));
        verify(redis).delete(RedisLoginFailureTracker.pairWaitKey(E, C));
        verify(redis).delete(RedisLoginFailureTracker.pairFailuresKey(E, other));
        verify(redis).delete(RedisLoginFailureTracker.pairWaitKey(E, other));
        verify(redis).delete(RedisLoginFailureTracker.clientsKey(E));
        verify(redis).delete(RedisLoginFailureTracker.accountFailuresKey(E));
        verify(redis).delete(RedisLoginFailureTracker.accountWaitKey(E));
    }
}
