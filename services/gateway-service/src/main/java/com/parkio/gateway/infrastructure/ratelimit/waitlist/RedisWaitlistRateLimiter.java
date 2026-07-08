package com.parkio.gateway.infrastructure.ratelimit.waitlist;

import com.parkio.gateway.application.waitlist.WaitlistProperties;
import com.parkio.gateway.application.waitlist.WaitlistRateLimitExceededException;
import com.parkio.gateway.application.waitlist.WaitlistRateLimiter;
import java.time.Duration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class RedisWaitlistRateLimiter implements WaitlistRateLimiter {

    private final ReactiveStringRedisTemplate redis;
    private final WaitlistProperties properties;

    public RedisWaitlistRateLimiter(ReactiveStringRedisTemplate redis, WaitlistProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public Mono<Void> check(String ipHash, String emailHash) {
        return checkKey("waitlist:ip:" + ipHash, properties.getIpRateLimit())
                .then(checkKey("waitlist:email:" + emailHash, properties.getEmailRateLimit()));
    }

    private Mono<Void> checkKey(String key, WaitlistProperties.RateLimit limit) {
        return redis.opsForValue()
                .increment(key)
                .flatMap(count -> {
                    Mono<Boolean> expire = count == 1
                            ? redis.expire(key, limit.getWindow())
                            : Mono.just(Boolean.TRUE);
                    if (count > limit.getMaxAttempts()) {
                        return expire.then(Mono.error(new WaitlistRateLimitExceededException()));
                    }
                    return expire.then();
                });
    }
}
