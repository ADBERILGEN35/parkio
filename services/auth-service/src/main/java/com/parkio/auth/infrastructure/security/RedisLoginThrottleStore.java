package com.parkio.auth.infrastructure.security;

import com.parkio.auth.application.LoginThrottleStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis primitives for the login throttle (CL-F15). Counters are INCR + EXPIRE (the window restarts on
 * every failure), markers are SET with a lifetime, plain sets are SADD + EXPIRE, and timed sets are
 * sorted sets scored by each member's expiry (epoch milliseconds); expired members are pruned on every
 * mark and the key's own lifetime is refreshed to the longest member lifetime.
 */
@Component
public class RedisLoginThrottleStore implements LoginThrottleStore {

    private final StringRedisTemplate redis;
    private final Clock clock;

    @Autowired
    public RedisLoginThrottleStore(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    RedisLoginThrottleStore(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public long increment(String key, Duration window) {
        Long value = redis.opsForValue().increment(key);
        redis.expire(key, window);
        return value == null ? 1L : value;
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
    public void markTimed(String key, String member, Duration ttl) {
        long now = clock.millis();
        redis.opsForZSet().add(key, member, now + ttl.toMillis());
        redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, now);
        redis.expire(key, ttl);
    }

    @Override
    public boolean isTimedMember(String key, String member) {
        Double expiresAt = redis.opsForZSet().score(key, member);
        return expiresAt != null && expiresAt > clock.millis();
    }

    @Override
    public void delete(Collection<String> keys) {
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
