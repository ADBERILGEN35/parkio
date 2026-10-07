package com.parkio.auth.infrastructure.security;

import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.LoginThrottlePolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed login throttle (CL-F15). Keys carry SHA-256 digests of the normalized e-mail
 * and of the client key, never the raw values, and no password or token material.
 *
 * <pre>
 * auth:login:v2:pair:{email}:{client}:failures   INCR per failure, TTL PAIR_WINDOW
 * auth:login:v2:pair:{email}:{client}:wait       set when a pair tier applies, TTL = the delay
 * auth:login:v2:account:{email}:failures         INCR per failure, TTL ACCOUNT_WINDOW
 * auth:login:v2:account:{email}:wait             set when the soft cap applies, TTL = the delay
 * auth:login:v2:account:{email}:clients          SET of client digests seen, TTL PAIR_WINDOW
 * </pre>
 *
 * The pre-CL-F15 keys ({@code auth:login:failures:*}, {@code auth:login:lock:*}) are not read
 * any more and expire on their own (at most 24 h and 1 h respectively).
 */
@Component
public class RedisLoginFailureTracker implements LoginFailureTracker {

    private static final String PREFIX = "auth:login:v2:";

    private final StringRedisTemplate redis;

    public RedisLoginFailureTracker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Duration retryAfter(String normalizedEmail, String clientKey, Instant now) {
        String email = digest(normalizedEmail);
        String client = digest(clientKey);
        return LoginThrottlePolicy.max(remaining(pairWaitKey(email, client)), remaining(accountWaitKey(email)));
    }

    @Override
    public LoginFailureOutcome recordFailure(String normalizedEmail, String clientKey, Instant now) {
        String email = digest(normalizedEmail);
        String client = digest(clientKey);

        long pairFailures = increment(pairFailuresKey(email, client), LoginThrottlePolicy.PAIR_WINDOW);
        redis.opsForSet().add(clientsKey(email), client);
        redis.expire(clientsKey(email), LoginThrottlePolicy.PAIR_WINDOW);
        long accountFailures = increment(accountFailuresKey(email), LoginThrottlePolicy.ACCOUNT_WINDOW);

        Duration pairDelay = LoginThrottlePolicy.pairDelay(pairFailures);
        if (!pairDelay.isZero()) {
            redis.opsForValue().set(pairWaitKey(email, client), Long.toString(pairFailures), pairDelay);
        }
        Duration accountDelay = LoginThrottlePolicy.accountDelay(accountFailures);
        if (!accountDelay.isZero()) {
            redis.opsForValue().set(accountWaitKey(email), Long.toString(accountFailures), accountDelay);
        }
        Duration delay = LoginThrottlePolicy.max(pairDelay, accountDelay);
        return new LoginFailureOutcome(pairFailures, accountFailures, delay, delay.isZero() ? null : now.plus(delay));
    }

    @Override
    public void clearAfterSuccess(String normalizedEmail, String clientKey) {
        String email = digest(normalizedEmail);
        String client = digest(clientKey);
        redis.delete(pairFailuresKey(email, client));
        redis.delete(pairWaitKey(email, client));
        redis.opsForSet().remove(clientsKey(email), client);
        redis.delete(accountFailuresKey(email));
        redis.delete(accountWaitKey(email));
    }

    @Override
    public void clearAccount(String normalizedEmail) {
        String email = digest(normalizedEmail);
        Set<String> clients = redis.opsForSet().members(clientsKey(email));
        if (clients != null) {
            for (String client : clients) {
                redis.delete(pairFailuresKey(email, client));
                redis.delete(pairWaitKey(email, client));
            }
        }
        redis.delete(clientsKey(email));
        redis.delete(accountFailuresKey(email));
        redis.delete(accountWaitKey(email));
    }

    private long increment(String key, Duration window) {
        Long value = redis.opsForValue().increment(key);
        redis.expire(key, window);
        return value == null ? 1L : value;
    }

    private Duration remaining(String waitKey) {
        Long ttl = redis.getExpire(waitKey, TimeUnit.SECONDS);
        // -2: no key; -1: no expiry (never written by this class) -> treat as not waiting.
        return ttl == null || ttl <= 0 ? Duration.ZERO : Duration.ofSeconds(ttl);
    }

    static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String pairFailuresKey(String email, String client) {
        return PREFIX + "pair:" + email + ":" + client + ":failures";
    }

    static String pairWaitKey(String email, String client) {
        return PREFIX + "pair:" + email + ":" + client + ":wait";
    }

    static String accountFailuresKey(String email) {
        return PREFIX + "account:" + email + ":failures";
    }

    static String accountWaitKey(String email) {
        return PREFIX + "account:" + email + ":wait";
    }

    static String clientsKey(String email) {
        return PREFIX + "account:" + email + ":clients";
    }
}
