package com.parkio.auth.infrastructure.security;

import com.parkio.auth.application.LoginFailureTracker;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed per-account failed-login tracker. Keys are based on normalized
 * email only and contain no raw passwords or token material.
 */
@Component
public class RedisLoginFailureTracker implements LoginFailureTracker {

    private static final String ATTEMPTS_PREFIX = "auth:login:failures:";
    private static final String LOCK_PREFIX = "auth:login:lock:";
    private static final Duration ATTEMPTS_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public RedisLoginFailureTracker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean isLocked(String normalizedEmail, Instant now) {
        return Boolean.TRUE.equals(redis.hasKey(lockKey(normalizedEmail)));
    }

    @Override
    public LoginFailureOutcome recordFailure(String normalizedEmail, Instant now) {
        String attemptsKey = attemptsKey(normalizedEmail);
        long failures = incrementFailures(attemptsKey);
        redis.expire(attemptsKey, ATTEMPTS_TTL);

        Duration lockDuration = lockDurationFor(failures);
        if (lockDuration.isZero()) {
            return new LoginFailureOutcome(failures, false, null);
        }

        redis.opsForValue().set(lockKey(normalizedEmail), Long.toString(failures), lockDuration);
        return new LoginFailureOutcome(failures, true, now.plus(lockDuration));
    }

    @Override
    public void reset(String normalizedEmail) {
        redis.delete(attemptsKey(normalizedEmail));
        redis.delete(lockKey(normalizedEmail));
    }

    private long incrementFailures(String attemptsKey) {
        Long failures = redis.opsForValue().increment(attemptsKey);
        return failures == null ? 1L : failures;
    }

    private static Duration lockDurationFor(long failures) {
        if (failures >= 20) {
            return Duration.ofHours(1);
        }
        if (failures >= 10) {
            return Duration.ofMinutes(5);
        }
        if (failures >= 5) {
            return Duration.ofSeconds(30);
        }
        return Duration.ZERO;
    }

    private static String attemptsKey(String normalizedEmail) {
        return ATTEMPTS_PREFIX + normalizedEmail;
    }

    private static String lockKey(String normalizedEmail) {
        return LOCK_PREFIX + normalizedEmail;
    }
}
