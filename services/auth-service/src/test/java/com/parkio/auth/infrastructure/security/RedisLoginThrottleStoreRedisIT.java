package com.parkio.auth.infrastructure.security;

import static com.parkio.auth.infrastructure.security.RedisLoginThrottleStore.ADD_TO_SET;
import static com.parkio.auth.infrastructure.security.RedisLoginThrottleStore.INCREMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * #606: a throttle counter or plain set gets its lifetime in the same command as its write, so a
 * connection lost after the first command can never leave a key that lives until the next write. The
 * failure is injected: the store's template gets one connection, and every later one fails as a
 * connection that dropped between two commands does. A healthy template then reads what Redis kept.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class RedisLoginThrottleStoreRedisIT {

    private static final Duration WINDOW = Duration.ofHours(24);
    private static final String COUNTER = "auth:login:v3:test:pair:e:c:failures";
    private static final String SET = "auth:login:v3:test:account:e:clients";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void flush() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void aConnectionLostAfterTheFirstCommandLeavesNoCounterWithoutItsWindow() {
        RedisLoginThrottleStore store = new RedisLoginThrottleStore(templateThatLosesItsConnectionAfter(1));

        Throwable lost = catchThrowable(() -> store.increment(COUNTER, WINDOW));

        assertThat(redis.getExpire(COUNTER, TimeUnit.MILLISECONDS)).as("the counter's remaining lifetime (ms)")
                .isBetween(WINDOW.minusMinutes(1).toMillis(), WINDOW.toMillis());
        assertThat(redis.opsForValue().get(COUNTER)).isEqualTo("1");
        assertThat(lost).as("one command per increment, so the lost connection is never reached").isNull();
    }

    @Test
    void aConnectionLostAfterTheFirstCommandLeavesNoSetWithoutItsLifetime() {
        RedisLoginThrottleStore store = new RedisLoginThrottleStore(templateThatLosesItsConnectionAfter(1));

        Throwable lost = catchThrowable(() -> store.addToSet(SET, "client-a", WINDOW));

        assertThat(redis.getExpire(SET, TimeUnit.MILLISECONDS)).as("the set's remaining lifetime (ms)")
                .isBetween(WINDOW.minusMinutes(1).toMillis(), WINDOW.toMillis());
        assertThat(redis.opsForSet().members(SET)).containsExactly("client-a");
        assertThat(lost).as("one command per add, so the lost connection is never reached").isNull();
    }

    /** The window semantics stay: every failure restarts the counter's window. */
    @Test
    void everyIncrementRestartsTheCountersWindow() {
        RedisLoginThrottleStore store = new RedisLoginThrottleStore(redis);
        assertThat(store.increment(COUNTER, WINDOW)).isEqualTo(1);
        redis.expire(COUNTER, Duration.ofSeconds(5));

        assertThat(store.increment(COUNTER, WINDOW)).isEqualTo(2);

        assertThat(redis.getExpire(COUNTER, TimeUnit.MILLISECONDS))
                .isBetween(WINDOW.minusMinutes(1).toMillis(), WINDOW.toMillis());
    }

    /** Every add restarts the set's lifetime, also for a member the set already holds. */
    @Test
    void everyAddRestartsTheSetsLifetime() {
        RedisLoginThrottleStore store = new RedisLoginThrottleStore(redis);
        store.addToSet(SET, "client-a", WINDOW);
        redis.expire(SET, Duration.ofSeconds(5));

        store.addToSet(SET, "client-a", WINDOW);

        assertThat(redis.getExpire(SET, TimeUnit.MILLISECONDS))
                .isBetween(WINDOW.minusMinutes(1).toMillis(), WINDOW.toMillis());
        assertThat(redis.opsForSet().members(SET)).containsExactly("client-a");
    }

    /**
     * Redis keeps a script's earlier writes when a later command fails, so each script checks its lifetime
     * argument before it writes: whatever a caller sends, a counter or set never lands without a lifetime.
     */
    @Test
    void theScriptsRefuseAMalformedLifetimeBeforeTheyWrite() {
        for (String malformed : List.of("0", "-5", "007", "1.5", "1e3", "0x10", " 10", "", "abc",
                "1000000000000000000")) {
            assertThat(catchThrowable(() -> redis.execute(INCREMENT, List.of(COUNTER), malformed)))
                    .as("increment with lifetime '%s'", malformed).isInstanceOf(DataAccessException.class);
            assertThat(catchThrowable(() -> redis.execute(ADD_TO_SET, List.of(SET), "client-a", malformed)))
                    .as("add with lifetime '%s'", malformed).isInstanceOf(DataAccessException.class);
        }
        assertThat(redis.hasKey(COUNTER)).isFalse();
        assertThat(redis.hasKey(SET)).isFalse();
        // The longest lifetime the scripts take (18 digits) is one PEXPIRE still accepts.
        assertThat(redis.execute(INCREMENT, List.of(COUNTER), "999999999999999999")).isEqualTo(1L);
        assertThat(redis.execute(ADD_TO_SET, List.of(SET), "client-a", "999999999999999999")).isEqualTo(1L);
        assertThat(redis.getExpire(COUNTER, TimeUnit.MILLISECONDS)).isGreaterThan(WINDOW.toMillis());
        assertThat(redis.getExpire(SET, TimeUnit.MILLISECONDS)).isGreaterThan(WINDOW.toMillis());
    }

    /**
     * A template over the test Redis that is served {@code commands} connections; every later request for
     * one fails with the exception a dropped connection raises, so a second command never reaches Redis.
     */
    private static StringRedisTemplate templateThatLosesItsConnectionAfter(int commands) {
        AtomicInteger served = new AtomicInteger();
        RedisConnectionFactory losing = new RedisConnectionFactory() {
            @Override
            public RedisConnection getConnection() {
                if (served.incrementAndGet() > commands) {
                    throw new RedisConnectionFailureException("Connection lost (injected by the test, #606)");
                }
                return connectionFactory.getConnection();
            }

            @Override
            public RedisClusterConnection getClusterConnection() {
                throw new UnsupportedOperationException("standalone Redis only");
            }

            @Override
            public boolean getConvertPipelineAndTxResults() {
                return connectionFactory.getConvertPipelineAndTxResults();
            }

            @Override
            public RedisSentinelConnection getSentinelConnection() {
                throw new UnsupportedOperationException("standalone Redis only");
            }

            @Override
            public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
                return connectionFactory.translateExceptionIfPossible(ex);
            }
        };
        return new StringRedisTemplate(losing);
    }
}
