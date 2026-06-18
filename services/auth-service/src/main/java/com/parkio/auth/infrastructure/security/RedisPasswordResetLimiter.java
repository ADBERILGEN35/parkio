package com.parkio.auth.infrastructure.security;

import com.parkio.auth.application.PasswordResetLimiter;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisPasswordResetLimiter implements PasswordResetLimiter {

    private static final String PREFIX = "auth:password-reset:";

    private final StringRedisTemplate redis;
    private final Duration cooldown;

    public RedisPasswordResetLimiter(
            StringRedisTemplate redis,
            @Value("${parkio.security.password-reset.request-cooldown:PT5M}") Duration cooldown) {
        this.redis = redis;
        this.cooldown = cooldown;
    }

    @Override
    public boolean tryAcquire(String normalizedEmail) {
        Boolean acquired = redis.opsForValue().setIfAbsent(PREFIX + normalizedEmail, "1", cooldown);
        return Boolean.TRUE.equals(acquired);
    }
}
