package com.parkio.auth.infrastructure.security;

import com.parkio.auth.application.VerificationResendLimiter;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisVerificationResendLimiter implements VerificationResendLimiter {

    private static final String PREFIX = "auth:email-verification:resend:";

    private final StringRedisTemplate redis;
    private final Duration cooldown;

    public RedisVerificationResendLimiter(
            StringRedisTemplate redis,
            @Value("${parkio.security.email-verification.resend-cooldown:PT5M}") Duration cooldown) {
        this.redis = redis;
        this.cooldown = cooldown;
    }

    @Override
    public boolean tryAcquire(String normalizedEmail) {
        Boolean acquired = redis.opsForValue().setIfAbsent(PREFIX + normalizedEmail, "1", cooldown);
        return Boolean.TRUE.equals(acquired);
    }
}
