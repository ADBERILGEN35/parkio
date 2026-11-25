package com.parkio.auth.infrastructure.security;

import com.parkio.auth.application.LoginThrottleStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis primitives for the login throttle (CL-F15). Counters are INCR + PEXPIRE (the window restarts on
 * every failure) and plain sets are SADD + PEXPIRE, each sent as one Lua script, so a write never lands
 * without its lifetime: a connection lost between two separate commands could leave a counter or set
 * that lives until its next write (#606). Markers are SET with a lifetime, and {@link #tryHold} is one
 * Lua script, so checking and claiming the running waits is atomic across auth-service instances.
 * That script touches several keys; on a Redis Cluster they would need a common hash tag (Parkio runs a
 * single Redis).
 */
@Component
public class RedisLoginThrottleStore implements LoginThrottleStore {

    /** KEYS: markers; ARGV: their durations in milliseconds (0: check only). Returns the longest remaining ms, or 0. */
    static final RedisScript<Long> TRY_HOLD = RedisScript.of("""
            local longest = 0
            for i = 1, #KEYS do
              local ttl = redis.call('PTTL', KEYS[i])
              if ttl > longest then longest = ttl end
            end
            if longest > 0 then return longest end
            for i = 1, #KEYS do
              local ms = tonumber(ARGV[i])
              if ms and ms > 0 then redis.call('SET', KEYS[i], '1', 'PX', ms) end
            end
            return 0
            """, Long.class);

    /**
     * KEYS: the counter; ARGV: its window in milliseconds. Increments, restarts the window and returns the
     * new value. The lifetime is checked before the write, because Redis keeps a script's earlier writes
     * when a later command fails: 1 to 18 decimal digits is a lifetime PEXPIRE always accepts, so nothing
     * can fail after the write.
     */
    static final RedisScript<Long> INCREMENT = RedisScript.of("""
            if not string.find(ARGV[1], '^[1-9]%d*$') or #ARGV[1] > 18 then
              return redis.error_reply('ERR throttle lifetime must be 1 to 18 decimal digits of milliseconds')
            end
            local value = redis.call('INCR', KEYS[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return value
            """, Long.class);

    /**
     * KEYS: the set; ARGV: the member, then the set's lifetime in milliseconds (checked first, as in
     * {@link #INCREMENT}). Adds the member, restarts the lifetime and returns the number of members added.
     */
    static final RedisScript<Long> ADD_TO_SET = RedisScript.of("""
            if not string.find(ARGV[2], '^[1-9]%d*$') or #ARGV[2] > 18 then
              return redis.error_reply('ERR throttle lifetime must be 1 to 18 decimal digits of milliseconds')
            end
            local added = redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return added
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisLoginThrottleStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(String key, Duration window) {
        Long value = redis.execute(INCREMENT, List.of(key), lifetimeMillis(window));
        return value == null ? 1L : value;
    }

    @Override
    public long read(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0L : Long.parseLong(value);
    }

    @Override
    public void hold(String key, Duration duration) {
        redis.opsForValue().set(key, "1", duration);
    }

    @Override
    public Duration remaining(String key) {
        Long millis = redis.getExpire(key, TimeUnit.MILLISECONDS);
        // -2: no key; -1: no expiry (never written by the throttle) -> not waiting.
        return millis == null || millis <= 0 ? Duration.ZERO : Duration.ofMillis(millis);
    }

    @Override
    public Duration tryHold(Map<String, Duration> markers) {
        if (markers.isEmpty()) {
            return Duration.ZERO;
        }
        List<String> keys = new ArrayList<>(markers.keySet());
        Object[] millis = markers.values().stream().map(d -> Long.toString(Math.max(0, d.toMillis()))).toArray();
        Long longest = redis.execute(TRY_HOLD, keys, millis);
        return longest == null || longest <= 0 ? Duration.ZERO : Duration.ofMillis(longest);
    }

    @Override
    public void addToSet(String key, String member, Duration keyTtl) {
        redis.execute(ADD_TO_SET, List.of(key), member, lifetimeMillis(keyTtl));
    }

    @Override
    public Set<String> setMembers(String key) {
        Set<String> members = redis.opsForSet().members(key);
        return members == null ? Set.of() : members;
    }

    @Override
    public Set<String> keysWithPrefix(String prefix) {
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(500).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }

    @Override
    public void delete(Collection<String> keys) {
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /** A lifetime in whole milliseconds; under 1 ms PEXPIRE would delete the key, so it is refused here. */
    private static String lifetimeMillis(Duration lifetime) {
        long millis = lifetime.toMillis();
        if (millis < 1) {
            throw new IllegalArgumentException("A throttle lifetime must be at least 1 ms, not " + lifetime);
        }
        return Long.toString(millis);
    }
}
