package com.parkio.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.LoginThrottlePolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
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
 * CL-F15 against a real Redis (the production image family): the acceptance scenarios of the
 * task, with real TTLs. Attacker traffic from one client does not lock the user on another;
 * brute force on one client is throttled; a reset clears every client's state.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class RedisLoginFailureTrackerRedisIT {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final String EMAIL = "user@example.com";
    private static final String USER_CLIENT = "198.51.100.7";
    private static final String ATTACKER_CLIENT = "203.0.113.9";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private RedisLoginFailureTracker tracker;

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
        tracker = new RedisLoginFailureTracker(redis);
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
    void bruteForceOnOneClientIsThrottledWithRealTtls() throws InterruptedException {
        LoginFailureTracker.LoginFailureOutcome fifth = null;
        for (int i = 0; i < 5; i++) {
            fifth = tracker.recordFailure(EMAIL, ATTACKER_CLIENT, NOW);
        }
        assertThat(fifth).isNotNull();
        assertThat(fifth.pairFailures()).isEqualTo(5);
        assertThat(fifth.delay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(tracker.retryAfter(EMAIL, ATTACKER_CLIENT, NOW)).isBetween(Duration.ofSeconds(28), Duration.ofSeconds(30));

        // The counters outlive the wait: the pair window is 24 h, the account window 1 h.
        String e = RedisLoginFailureTracker.digest(EMAIL);
        String c = RedisLoginFailureTracker.digest(ATTACKER_CLIENT);
        assertThat(redis.getExpire(RedisLoginFailureTracker.pairFailuresKey(e, c)))
                .isBetween(Duration.ofHours(23).toSeconds(), Duration.ofHours(24).toSeconds());
        assertThat(redis.getExpire(RedisLoginFailureTracker.accountFailuresKey(e)))
                .isBetween(Duration.ofMinutes(59).toSeconds(), Duration.ofHours(1).toSeconds());
        assertThat(redis.opsForSet().members(RedisLoginFailureTracker.clientsKey(e))).isEqualTo(Set.of(c));
        // No raw e-mail or address anywhere in the key space.
        assertThat(redis.keys("*")).allSatisfy(key -> assertThat(key).doesNotContain(EMAIL).doesNotContain(ATTACKER_CLIENT));
    }

    @Test
    void successClearsThisClientAndTheAccountCounterButNotTheAttackersPair() {
        for (int i = 0; i < 20; i++) {
            tracker.recordFailure(EMAIL, ATTACKER_CLIENT, NOW);
        }
        tracker.recordFailure(EMAIL, USER_CLIENT, NOW);

        tracker.clearAfterSuccess(EMAIL, USER_CLIENT);

        String e = RedisLoginFailureTracker.digest(EMAIL);
        assertThat(redis.hasKey(RedisLoginFailureTracker.accountFailuresKey(e))).isFalse();
        assertThat(redis.hasKey(RedisLoginFailureTracker.pairFailuresKey(e, RedisLoginFailureTracker.digest(USER_CLIENT)))).isFalse();
        assertThat(tracker.retryAfter(EMAIL, ATTACKER_CLIENT, NOW)).isGreaterThan(Duration.ofMinutes(59));
    }

    @Test
    void passwordResetClearsEveryClientsState() {
        for (int i = 0; i < 10; i++) {
            tracker.recordFailure(EMAIL, ATTACKER_CLIENT, NOW);
            tracker.recordFailure(EMAIL, "203.0.113." + (20 + i), NOW);
        }
        assertThat(tracker.retryAfter(EMAIL, ATTACKER_CLIENT, NOW)).isGreaterThan(Duration.ofMinutes(4));

        tracker.clearAccount(EMAIL);

        assertThat(tracker.retryAfter(EMAIL, ATTACKER_CLIENT, NOW)).isEqualTo(Duration.ZERO);
        assertThat(tracker.retryAfter(EMAIL, "203.0.113.25", NOW)).isEqualTo(Duration.ZERO);
        assertThat(redis.keys("auth:login:v2:*")).isEmpty();
    }

    @Test
    void accountSoftCapAppliesToEveryClientAndExpiresOnItsOwn() {
        for (int i = 0; i < LoginThrottlePolicy.ACCOUNT_SOFT_CAP; i++) {
            tracker.recordFailure(EMAIL, "203.0.113." + (10 + i / 4), NOW);
        }

        assertThat(tracker.retryAfter(EMAIL, USER_CLIENT, NOW))
                .isBetween(LoginThrottlePolicy.ACCOUNT_SOFT_DELAY.minusSeconds(2), LoginThrottlePolicy.ACCOUNT_SOFT_DELAY);
        String e = RedisLoginFailureTracker.digest(EMAIL);
        assertThat(redis.getExpire(RedisLoginFailureTracker.accountWaitKey(e)))
                .isBetween(1L, LoginThrottlePolicy.ACCOUNT_SOFT_DELAY.toSeconds());
    }
}
