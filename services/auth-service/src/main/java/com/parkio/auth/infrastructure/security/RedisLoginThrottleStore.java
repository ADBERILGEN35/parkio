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
 * Redis primitives for the login throttle (CL-F15). Counters are INCR + EXPIRE (the window restarts on
 * every failure), markers are SET with a lifetime, plain sets are SADD + EXPIRE, and {@link #tryHold}
 * is one Lua script, so checking and claiming the running waits is atomic across auth-service instances.
 * The script touches several keys; on a Redis Cluster they would need a common hash tag (Parkio runs a
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

    private final StringRedisTemplate redis;

    public RedisLoginThrottleStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(String key, Duration window) {
        Long value = redis.opsForValue().increment(key);
        redis.expire(key, window);
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
        redis.opsForSet().add(key, member);
        redis.expire(key, keyTtl);
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
}
