package com.parkio.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The Redis commands behind each throttle primitive (CL-F15 v2). The behaviour against a real Redis,
 * with TTL expiry and key types, is in {@code ThrottledLoginFailureTrackerRedisIT}; a connection lost
 * between commands (#606) is in {@code RedisLoginThrottleStoreRedisIT}.
 */
class RedisLoginThrottleStoreTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SetOperations<String, String> sets;
    private RedisLoginThrottleStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        store = new RedisLoginThrottleStore(redis);
    }

    /** One script increments and restarts the window, so the count never lands without its lifetime (#606). */
    @Test
    @SuppressWarnings("unchecked")
    void incrementIsOneScriptThatIncrementsAndRestartsTheWindow() {
        when(redis.execute(any(RedisScript.class), eq(List.of("k")), eq("86400000"))).thenReturn(3L);

        assertThat(store.increment("k", Duration.ofHours(24))).isEqualTo(3L);

        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redis).execute(script.capture(), eq(List.of("k")), eq("86400000"));
        verifyNoMoreInteractions(redis);
        verifyNoInteractions(values);
        assertThat(script.getValue()).isSameAs(RedisLoginThrottleStore.INCREMENT);
        String lua = RedisLoginThrottleStore.INCREMENT.getScriptAsString();
        assertThat(lua).contains("redis.call('INCR', KEYS[1])").contains("redis.call('PEXPIRE', KEYS[1], ARGV[1])");
        assertThat(lua.indexOf("error_reply")).as("the lifetime is checked before the write")
                .isNotNegative().isLessThan(lua.indexOf("'INCR'"));
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

    /** One script adds the member and restarts the set's lifetime, so the set never lives without one (#606). */
    @Test
    @SuppressWarnings("unchecked")
    void plainSetsAreOneScriptThatAddsAndRestartsTheKeyLifetime() {
        store.addToSet("s", "m", Duration.ofHours(24));

        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redis).execute(script.capture(), eq(List.of("s")), eq("m"), eq("86400000"));
        verifyNoMoreInteractions(redis);
        verifyNoInteractions(sets);
        assertThat(script.getValue()).isSameAs(RedisLoginThrottleStore.ADD_TO_SET);
        String lua = RedisLoginThrottleStore.ADD_TO_SET.getScriptAsString();
        assertThat(lua).contains("redis.call('SADD', KEYS[1], ARGV[1])")
                .contains("redis.call('PEXPIRE', KEYS[1], ARGV[2])");
        assertThat(lua.indexOf("error_reply")).as("the lifetime is checked before the write")
                .isNotNegative().isLessThan(lua.indexOf("'SADD'"));
    }

    @Test
    void setMembersTreatsAMissingSetAsEmpty() {
        when(sets.members("s")).thenReturn(null);
        assertThat(store.setMembers("s")).isEmpty();
        when(sets.members("t")).thenReturn(Set.of("a"));
        assertThat(store.setMembers("t")).containsExactly("a");
    }

    /** Under 1 ms, PEXPIRE would delete the key just written, so such a lifetime is refused before anything is sent. */
    @Test
    void aLifetimeUnderOneMillisecondIsRefusedBeforeAnythingIsSent() {
        for (Duration lifetime : List.of(Duration.ZERO, Duration.ofNanos(999_999), Duration.ofMillis(-1))) {
            assertThatThrownBy(() -> store.increment("k", lifetime)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.addToSet("s", "m", lifetime)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(redis, values, sets);
    }

    @Test
    void readParsesTheCounterAndTreatsAMissingOneAsZero() {
        when(values.get("c")).thenReturn("42");
        assertThat(store.read("c")).isEqualTo(42L);
        when(values.get("missing")).thenReturn(null);
        assertThat(store.read("missing")).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tryHoldRunsOneScriptWithTheMarkersAndTheirMilliseconds() {
        Map<String, Duration> waits = new LinkedHashMap<>();
        waits.put("pair", Duration.ofSeconds(30));
        waits.put("account", Duration.ZERO);
        when(redis.execute(eq(RedisLoginThrottleStore.TRY_HOLD), eq(List.of("pair", "account")), eq("30000"), eq("0")))
                .thenReturn(0L, 12_345L);

        assertThat(store.tryHold(waits)).isZero();
        assertThat(store.tryHold(waits)).isEqualTo(Duration.ofMillis(12_345));
        assertThat(store.tryHold(Map.of())).isZero();
        assertThat(RedisLoginThrottleStore.TRY_HOLD.getScriptAsString())
                .contains("PTTL").contains("'SET', KEYS[i], '1', 'PX', ms").doesNotContain("NX");
    }

    @Test
    @SuppressWarnings("unchecked")
    void keysWithPrefixScansIncrementallyAndClosesTheCursor() {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("auth:login:v2:known:e:a", "auth:login:v2:known:e:b");
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);

        assertThat(store.keysWithPrefix("auth:login:v2:known:e:"))
                .containsExactlyInAnyOrder("auth:login:v2:known:e:a", "auth:login:v2:known:e:b");
        verify(cursor).close();
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
