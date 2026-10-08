package com.parkio.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.LoginThrottlePolicy;
import com.parkio.auth.application.ThrottledLoginFailureTracker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
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
 * CL-F15 v2 against a real Redis (the production image family), with real TTLs: per-client tiers,
 * account tiers for unknown clients only, known clients, reset and erasure clearing, compatibility with
 * state written by the v1 tracker, and no raw identifiers in the key space.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ThrottledLoginFailureTrackerRedisIT {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final String EMAIL = "user@example.com";
    private static final String USER_CLIENT = "198.51.100.7";
    private static final String ATTACKER_CLIENT = "203.0.113.9";
    private static final String PREFIX = "auth:login:v2:";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private ThrottledLoginFailureTracker tracker;

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
        tracker = new ThrottledLoginFailureTracker(new RedisLoginThrottleStore(redis));
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

        String e = digest(EMAIL);
        String c = digest(ATTACKER_CLIENT);
        assertThat(redis.getExpire(PREFIX + "pair:" + e + ":" + c + ":failures"))
                .isBetween(Duration.ofHours(23).toSeconds(), Duration.ofHours(24).toSeconds());
        assertThat(redis.getExpire(PREFIX + "account:" + e + ":failures"))
                .isBetween(Duration.ofMinutes(59).toSeconds(), Duration.ofHours(1).toSeconds());
        assertThat(redis.type(PREFIX + "account:" + e + ":clients").code()).isEqualTo("set");
        assertThat(redis.keys("*")).allSatisfy(key -> assertThat(key).doesNotContain(EMAIL).doesNotContain(ATTACKER_CLIENT));
    }

    @Test
    void accountTiersDelayUnknownClientsAndNotAKnownClient() {
        tracker.clearAfterSuccess(EMAIL, USER_CLIENT);
        String e = digest(EMAIL);
        assertThat(redis.type(PREFIX + "account:" + e + ":known").code()).isEqualTo("zset");
        assertThat(redis.getExpire(PREFIX + "account:" + e + ":known"))
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

        String e = digest(EMAIL);
        assertThat(redis.opsForValue().get(PREFIX + "account:" + e + ":failures")).isEqualTo("21");
        assertThat(redis.hasKey(PREFIX + "pair:" + e + ":" + digest(USER_CLIENT) + ":failures")).isFalse();
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
        assertThat(redis.keys(PREFIX + "pair:*")).isEmpty();
        String e = digest(EMAIL);
        assertThat(redis.opsForValue().get(PREFIX + "account:" + e + ":failures")).isEqualTo("20");
        assertThat(redis.opsForZSet().score(PREFIX + "account:" + e + ":known", digest(USER_CLIENT))).isNotNull();
    }

    /** State written by the v1 tracker (same key names and types) stays usable after the upgrade. */
    @Test
    void stateWrittenByTheV1TrackerIsReadAndClearedWithoutTypeErrors() {
        String e = digest(EMAIL);
        String c = digest(ATTACKER_CLIENT);
        redis.opsForValue().set(PREFIX + "pair:" + e + ":" + c + ":failures", "7", Duration.ofHours(24));
        redis.opsForValue().set(PREFIX + "pair:" + e + ":" + c + ":wait", "7", Duration.ofSeconds(30));
        redis.opsForSet().add(PREFIX + "account:" + e + ":clients", c);
        redis.opsForValue().set(PREFIX + "account:" + e + ":failures", "7", Duration.ofHours(1));

        assertThat(tracker.retryAfter(EMAIL, ATTACKER_CLIENT, NOW)).isBetween(Duration.ofSeconds(28), Duration.ofSeconds(30));
        assertThat(tracker.recordFailure(EMAIL, USER_CLIENT, NOW).accountFailures()).isEqualTo(8);

        tracker.clearAfterPasswordReset(EMAIL, null);

        assertThat(redis.keys(PREFIX + "pair:*")).isEmpty();
        assertThat(tracker.retryAfter(EMAIL, ATTACKER_CLIENT, NOW)).isEqualTo(Duration.ZERO);
    }

    @Test
    void forgetAccountRemovesEveryKeyOfTheAccountOnly() {
        tracker.clearAfterSuccess(EMAIL, USER_CLIENT);
        for (int i = 0; i < LoginThrottlePolicy.ACCOUNT_FIRST_CAP; i++) {
            tracker.recordFailure(EMAIL, "203.0.113." + (i % 25), NOW);
        }
        tracker.recordFailure("neighbour@example.com", ATTACKER_CLIENT, NOW);

        tracker.forgetAccount(EMAIL);

        assertThat(redis.keys("*" + digest(EMAIL) + "*")).isEmpty();
        assertThat(redis.keys("*" + digest("neighbour@example.com") + "*")).isNotEmpty();
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
