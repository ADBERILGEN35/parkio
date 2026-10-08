package com.parkio.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.LoginThrottlePolicy;
import com.parkio.auth.application.LoginThrottleTestKeys;
import com.parkio.auth.application.ThrottledLoginFailureTracker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * CL-F15 v3 against a real Redis (the production image family), with real TTLs: per-client tiers,
 * account tiers for unknown clients only, known clients, reset and erasure clearing, the keyed key
 * space (no raw identifiers; a rotation's overlap reads the previous secret's entries and writes only
 * under the current one), and the admission script's atomicity.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ThrottledLoginFailureTrackerRedisIT {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final String EMAIL = "user@example.com";
    private static final String USER_CLIENT = "198.51.100.7";
    private static final String ATTACKER_CLIENT = "203.0.113.9";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private ThrottledLoginFailureTracker tracker;
    private String prefix;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void flush() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        tracker = new ThrottledLoginFailureTracker(new RedisLoginThrottleStore(redis), LoginThrottleTestKeys.CURRENT);
        prefix = tracker.prefix();
    }

    @Test
    void attackerFailuresFromOneClientDoNotLockTheUserOnAnotherClient() {
        for (int i = 0; i < 20; i++) {
            tracker.recordFailure(EMAIL, ATTACKER_CLIENT, NOW);
        }
        assertThat(tracker.retryAfter(EMAIL, ATTACKER_CLIENT, NOW)).isBetween(Duration.ofMinutes(59), Duration.ofHours(1));
        assertThat(tracker.retryAfter(EMAIL, USER_CLIENT, NOW)).isEqualTo(Duration.ZERO);
        assertThat(tracker.retryAfter("neighbour@example.com", ATTACKER_CLIENT, NOW)).isEqualTo(Duration.ZERO);
    }

    @Test
    void bruteForceOnOneClientIsThrottledWithRealTtls() {
        LoginFailureTracker.LoginFailureOutcome fifth = null;
        for (int i = 0; i < 5; i++) {
            fifth = tracker.recordFailure(EMAIL, ATTACKER_CLIENT, NOW);
        }
        assertThat(fifth).isNotNull();
        assertThat(fifth.pairFailures()).isEqualTo(5);
        assertThat(fifth.delay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(tracker.retryAfter(EMAIL, ATTACKER_CLIENT, NOW)).isBetween(Duration.ofSeconds(28), Duration.ofSeconds(30));
        String e = tracker.digest(EMAIL);
        String c = tracker.digest(ATTACKER_CLIENT);
        assertThat(redis.getExpire(prefix + "pair:" + e + ":" + c + ":failures"))
                .isBetween(Duration.ofHours(23).toSeconds(), Duration.ofHours(24).toSeconds());
        assertThat(redis.getExpire(prefix + "account:" + e + ":failures"))
                .isBetween(Duration.ofMinutes(59).toSeconds(), Duration.ofHours(1).toSeconds());
        assertThat(redis.type(prefix + "account:" + e + ":clients").code()).isEqualTo("set");
        assertThat(redis.keys("*")).isNotEmpty().allSatisfy(key -> assertThat(key)
                .startsWith("auth:login:v3:" + LoginThrottleTestKeys.CURRENT.kid() + ":")
                .doesNotContain(EMAIL).doesNotContain(ATTACKER_CLIENT));
    }

    @Test
    void accountTiersDelayUnknownClientsAndNotAKnownClient() {
        tracker.clearAfterSuccess(EMAIL, USER_CLIENT);
        String e = tracker.digest(EMAIL);
        String known = prefix + "known:" + e + ":" + tracker.digest(USER_CLIENT);
        assertThat(redis.type(known).code()).isEqualTo("string");
        assertThat(redis.getExpire(known))
                .isBetween(LoginThrottlePolicy.KNOWN_CLIENT_TTL.minusMinutes(1).toSeconds(), LoginThrottlePolicy.KNOWN_CLIENT_TTL.toSeconds());
        for (int i = 0; i < LoginThrottlePolicy.ACCOUNT_THIRD_CAP; i++) {
            tracker.recordFailure(EMAIL, "203.0.113." + (i % 200), NOW);
        }
        assertThat(tracker.retryAfter(EMAIL, "192.0.2.1", NOW))
                .isBetween(LoginThrottlePolicy.ACCOUNT_THIRD_DELAY.minusSeconds(2), LoginThrottlePolicy.ACCOUNT_THIRD_DELAY);
        assertThat(tracker.retryAfter(EMAIL, USER_CLIENT, NOW)).isEqualTo(Duration.ZERO);
        assertThat(tracker.recordFailure(EMAIL, USER_CLIENT, NOW).delay()).isEqualTo(Duration.ZERO);
    }

    @Test
    void successClearsThisClientKeepsTheAccountCounterAndTheAttackersPair() {
        for (int i = 0; i < 20; i++) {
            tracker.recordFailure(EMAIL, ATTACKER_CLIENT, NOW);
        }
        tracker.recordFailure(EMAIL, USER_CLIENT, NOW);
        tracker.clearAfterSuccess(EMAIL, USER_CLIENT);
        String e = tracker.digest(EMAIL);
        assertThat(redis.opsForValue().get(prefix + "account:" + e + ":failures")).isEqualTo("21");
        assertThat(redis.hasKey(prefix + "pair:" + e + ":" + tracker.digest(USER_CLIENT) + ":failures")).isFalse();
        assertThat(tracker.retryAfter(EMAIL, ATTACKER_CLIENT, NOW)).isGreaterThan(Duration.ofMinutes(59));
    }

    @Test
    void passwordResetClearsEveryPairAndMakesTheResettingClientKnown() {
        for (int i = 0; i < 10; i++) {
            tracker.recordFailure(EMAIL, ATTACKER_CLIENT, NOW);
            tracker.recordFailure(EMAIL, "203.0.113." + (20 + i), NOW);
        }
        assertThat(tracker.retryAfter(EMAIL, ATTACKER_CLIENT, NOW)).isGreaterThan(Duration.ofMinutes(4));
        tracker.clearAfterPasswordReset(EMAIL, USER_CLIENT);
        assertThat(tracker.retryAfter(EMAIL, ATTACKER_CLIENT, NOW)).isEqualTo(Duration.ZERO);
        assertThat(redis.keys(prefix + "pair:*")).isEmpty();
        String e = tracker.digest(EMAIL);
        assertThat(redis.opsForValue().get(prefix + "account:" + e + ":failures")).isEqualTo("20");
        assertThat(redis.hasKey(prefix + "known:" + e + ":" + tracker.digest(USER_CLIENT))).isTrue();
    }

    /** Only a successful authentication writes a known marker; a token refresh keeps it alive. */
    @Test
    void failuresAndAdmissionsNeverMakeAClientKnownButARefreshKeepsIt() {
        for (int i = 0; i < 3; i++) {
            tracker.admit(EMAIL, USER_CLIENT, NOW);
            tracker.recordFailure(EMAIL, USER_CLIENT, NOW);
        }
        assertThat(redis.keys(prefix + "known:*")).isEmpty();
        tracker.refreshKnownClient(EMAIL, USER_CLIENT);
        String known = prefix + "known:" + tracker.digest(EMAIL) + ":" + tracker.digest(USER_CLIENT);
        assertThat(redis.getExpire(known))
                .isBetween(LoginThrottlePolicy.KNOWN_CLIENT_TTL.minusMinutes(1).toSeconds(), LoginThrottlePolicy.KNOWN_CLIENT_TTL.toSeconds());
        tracker.refreshKnownClient(EMAIL, LoginFailureTracker.UNKNOWN_CLIENT);
        assertThat(redis.keys(prefix + "known:*")).hasSize(1);
    }

    /**
     * Key rotation on real Redis: entries written under the previous secret keep their effect during the
     * overlap (waits honoured, known client recognised), new entries go under the current secret only, and
     * a reset or an erasure clears both key ids.
     */
    @Test
    void rotationOverlapReadsThePreviousSecretsEntriesAndWritesOnlyUnderTheCurrentOne() {
        ThrottledLoginFailureTracker old = new ThrottledLoginFailureTracker(new RedisLoginThrottleStore(redis), LoginThrottleTestKeys.OLD);
        old.clearAfterSuccess(EMAIL, USER_CLIENT);
        for (int i = 0; i < 10; i++) {
            old.recordFailure(EMAIL, ATTACKER_CLIENT, NOW);
        }
        for (int i = 0; i < LoginThrottlePolicy.ACCOUNT_FIRST_CAP; i++) {
            old.recordFailure(EMAIL, "203.0.113." + (i % 200), NOW);
        }
        ThrottledLoginFailureTracker rotated = new ThrottledLoginFailureTracker(new RedisLoginThrottleStore(redis), LoginThrottleTestKeys.ROTATED);
        String oldPrefix = old.prefix();
        String newPrefix = rotated.prefix();
        assertThat(newPrefix).isNotEqualTo(oldPrefix);

        // The attacker's pair wait and the account wait written under the old secret still apply.
        assertThat(rotated.admit(EMAIL, ATTACKER_CLIENT, NOW)).isBetween(Duration.ofMinutes(4), Duration.ofMinutes(5));
        assertThat(rotated.retryAfter(EMAIL, "192.0.2.1", NOW)).isBetween(Duration.ofSeconds(8), LoginThrottlePolicy.ACCOUNT_FIRST_DELAY);
        // The known client from before the rotation is still exempt from the account wait.
        assertThat(rotated.retryAfter(EMAIL, USER_CLIENT, NOW)).isEqualTo(Duration.ZERO);
        assertThat(rotated.admit(EMAIL, USER_CLIENT, NOW)).isEqualTo(Duration.ZERO);
        // A new failure is written under the current secret only (the old key id's entries are untouched).
        int oldPairKeys = redis.keys(oldPrefix + "pair:*").size();
        assertThat(oldPairKeys).isGreaterThanOrEqualTo(51);
        rotated.recordFailure(EMAIL, "198.18.0.1", NOW);
        assertThat(redis.keys(newPrefix + "pair:*")).hasSize(1);
        assertThat(redis.keys(oldPrefix + "pair:*")).hasSize(oldPairKeys);
        assertThat(redis.keys("auth:login:v3:*")).allSatisfy(key -> assertThat(key)
                .doesNotContain(EMAIL).doesNotContain(ATTACKER_CLIENT).doesNotContain(USER_CLIENT));
        // A reset clears the pairs of both key ids; the account counter and its (old key id's) running
        // account wait stay, so the attacker, still unknown, may wait out that 10 s but no longer its 5 min pair.
        rotated.clearAfterPasswordReset(EMAIL, USER_CLIENT);
        assertThat(redis.keys(oldPrefix + "pair:*")).isEmpty();
        assertThat(redis.keys(newPrefix + "pair:*")).isEmpty();
        assertThat(rotated.retryAfter(EMAIL, ATTACKER_CLIENT, NOW)).isLessThanOrEqualTo(LoginThrottlePolicy.ACCOUNT_FIRST_DELAY);
        assertThat(rotated.retryAfter(EMAIL, USER_CLIENT, NOW)).isEqualTo(Duration.ZERO);
        // An erasure removes the account's entries under both key ids.
        rotated.forgetAccount(EMAIL);
        assertThat(redis.keys("*")).isEmpty();
        // After the overlap, a tracker without the previous secret does not read the old entries at all.
        old.clearAfterSuccess(EMAIL, USER_CLIENT);
        ThrottledLoginFailureTracker newOnly = new ThrottledLoginFailureTracker(new RedisLoginThrottleStore(redis), LoginThrottleTestKeys.NEW_ONLY);
        for (int i = 0; i < LoginThrottlePolicy.ACCOUNT_FIRST_CAP; i++) {
            newOnly.recordFailure(EMAIL, "203.0.113." + (i % 200), NOW);
        }
        assertThat(newOnly.retryAfter(EMAIL, USER_CLIENT, NOW))
                .isBetween(LoginThrottlePolicy.ACCOUNT_FIRST_DELAY.minusSeconds(2), LoginThrottlePolicy.ACCOUNT_FIRST_DELAY);
    }

    /** The admission script against a real Redis: many concurrent attempts, one evaluation per wait (#311 review B1). */
    @Test
    void concurrentAdmissionsAdmitExactlyOneAttemptPerAccountWait() throws Exception {
        for (int i = 0; i < LoginThrottlePolicy.ACCOUNT_THIRD_CAP; i++) {
            tracker.recordFailure(EMAIL, "203.0.113." + (i % 200), NOW);
        }
        redis.delete(prefix + "account:" + tracker.digest(EMAIL) + ":wait");
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Duration>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String client = "198.18.2." + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return tracker.admit(EMAIL, client, NOW);
                }));
            }
            start.countDown();
            long admitted = 0;
            for (Future<Duration> result : results) {
                Duration wait = result.get(30, TimeUnit.SECONDS);
                if (wait.isZero()) {
                    admitted++;
                } else {
                    assertThat(wait).isBetween(Duration.ofMinutes(4), LoginThrottlePolicy.ACCOUNT_THIRD_DELAY);
                }
            }
            assertThat(admitted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** The key names v1 (#306) reads are never written by v3, so a rollback finds nothing of a foreign type. */
    @Test
    void v3WritesNothingOutsideItsOwnNamespace() {
        tracker.clearAfterSuccess(EMAIL, USER_CLIENT);
        for (int i = 0; i < LoginThrottlePolicy.ACCOUNT_FIRST_CAP; i++) {
            tracker.recordFailure(EMAIL, ATTACKER_CLIENT, NOW);
        }
        tracker.admit(EMAIL, "198.18.3.1", NOW);
        tracker.clearAfterPasswordReset(EMAIL, USER_CLIENT);
        assertThat(redis.keys("*")).isNotEmpty().allSatisfy(key -> assertThat(key).startsWith(prefix));
        assertThat(redis.keys("auth:login:v2:*")).isEmpty();
    }

    @Test
    void forgetAccountRemovesEveryKeyOfTheAccountOnly() {
        tracker.clearAfterSuccess(EMAIL, USER_CLIENT);
        for (int i = 0; i < LoginThrottlePolicy.ACCOUNT_FIRST_CAP; i++) {
            tracker.recordFailure(EMAIL, "203.0.113." + (i % 25), NOW);
        }
        tracker.recordFailure("neighbour@example.com", ATTACKER_CLIENT, NOW);
        tracker.forgetAccount(EMAIL);
        assertThat(redis.keys("*" + tracker.digest(EMAIL) + "*")).isEmpty();
        assertThat(redis.keys("*" + tracker.digest("neighbour@example.com") + "*")).isNotEmpty();
    }
}
