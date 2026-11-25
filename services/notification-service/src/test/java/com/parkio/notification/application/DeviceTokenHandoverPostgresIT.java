package com.parkio.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.notification.application.command.RegisterDeviceTokenCommand;
import com.parkio.notification.application.event.UserErasureRequestedEvent;
import com.parkio.notification.domain.DevicePlatform;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.domain.exception.NotificationException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * H-05 (#546) on PostgreSQL with the Flyway schema: a push token is active for at most one account,
 * concurrent registrations of one token apply one after the other, and the erasure fence still
 * decides who may register.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DeviceTokenHandoverPostgresIT {

    /** The handover's lock_timeout (#601). */
    private static final Duration LOCK_BOUND = Duration.ofSeconds(5);
    /** Scheduling slack for a loaded runner; without a bound the wait never ends. */
    private static final Duration SLACK = Duration.ofSeconds(10);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired private NotificationApplicationService service;
    @Autowired private AccountErasureHandler erasure;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void registeringAHeldTokenDeactivatesThePreviousHoldersRow() {
        UUID previousHolder = UUID.randomUUID();
        UUID newHolder = UUID.randomUUID();
        String token = uniqueToken();
        DeviceToken previous = register(previousHolder, token);
        long versionBefore = versionOf(previous.id());

        register(newHolder, token);

        assertThat(activeHolders(token)).containsExactly(newHolder);
        assertThat(versionOf(previous.id())).isEqualTo(versionBefore + 1);
    }

    @Test
    void concurrentRegistrationsOfOneTokenLeaveOnlyTheLastOneActive() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String token = uniqueToken();
        CountDownLatch firstRegistered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstRegistration = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        register(first, token);
                        firstRegistered.countDown();
                        awaitRelease(releaseFirst);
                    }));
            assertThat(firstRegistered.await(30, TimeUnit.SECONDS)).isTrue();

            Future<?> secondRegistration = pool.submit(() -> register(second, token));
            // Without the token lock the second registration would miss the first one's
            // uncommitted row, commit, and leave both rows active.
            assertThat(awaitBlockedAdvisoryLock(Duration.ofSeconds(30)))
                    .as("the second registration waits for the first one's token lock")
                    .isTrue();
            assertThat(secondRegistration).isNotDone();

            releaseFirst.countDown();
            firstRegistration.get(30, TimeUnit.SECONDS);
            secondRegistration.get(30, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }

        assertThat(activeHolders(token)).containsExactly(second);
    }

    @Test
    void erasureFenceStillDecidesWhoMayRegister() {
        UUID erased = UUID.randomUUID();
        UUID holder = UUID.randomUUID();
        String token = uniqueToken();
        register(erased, token);
        erasure.handle(new UserErasureRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), erased, Instant.now()));
        register(holder, token);

        assertThatThrownBy(() -> register(erased, token)).isInstanceOf(NotificationException.class);

        assertThat(activeHolders(token)).containsExactly(holder);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", Long.class, erased))
                .isZero();
    }

    /** #601: a registration gives up on the token lock after the bound instead of waiting for its holder. */
    @Test
    void registrationWaitingOnTheTokenLockGivesUpAfterTheBound() throws Exception {
        UUID holder = UUID.randomUUID();
        UUID waiter = UUID.randomUUID();
        String token = uniqueToken();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holderTransaction = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        register(holder, token);
                        holding.countDown();
                        awaitRelease(release);
                    }));
            assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            Throwable failure = failureOf(pool.submit(() -> register(waiter, token)));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertThat(isLockTimeout(failure)).as("lock timeout, got %s", failure).isTrue();
            assertThat(waited).isGreaterThanOrEqualTo(LOCK_BOUND).isLessThan(LOCK_BOUND.plus(SLACK));
            release.countDown();
            holderTransaction.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(activeHolders(token)).containsExactly(holder);
        assertThat(rowsOf(waiter)).isZero();
    }

    /**
     * #601: the bound also ends a wait for another account's row lock. A delivery tick that deactivated the row
     * after DeviceNotRegistered holds it until its whole batch commits.
     */
    @Test
    void handoverWaitingOnAnotherAccountsRowGivesUpAfterTheBound() throws Exception {
        UUID previous = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        String token = uniqueToken();
        register(previous, token);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> rowLock = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbc.queryForObject("SELECT id FROM device_tokens WHERE user_id = ? AND token = ? FOR UPDATE",
                                UUID.class, previous, token);
                        holding.countDown();
                        awaitRelease(release);
                    }));
            assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            Throwable failure = failureOf(pool.submit(() -> register(next, token)));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertThat(isLockTimeout(failure)).as("lock timeout, got %s", failure).isTrue();
            assertThat(waited).isGreaterThanOrEqualTo(LOCK_BOUND).isLessThan(LOCK_BOUND.plus(SLACK));
            release.countDown();
            rowLock.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(activeHolders(token)).containsExactly(previous);
        assertThat(rowsOf(next)).isZero();
    }

    /** The failure of a call that must fail; a call still waiting after the bound and the slack fails the test. */
    private static Throwable failureOf(Future<?> call) throws Exception {
        try {
            call.get(LOCK_BOUND.plus(SLACK).toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException failed) {
            return failed.getCause();
        }
        throw new AssertionError("the registration succeeded instead of giving up on the lock");
    }

    /** PostgreSQL's lock_not_available (55P03), anywhere in the cause chain. */
    private static boolean isLockTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private long rowsOf(UUID userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", Long.class, userId);
    }

    private DeviceToken register(UUID userId, String token) {
        return service.registerDeviceToken(new RegisterDeviceTokenCommand(userId, token, DevicePlatform.ANDROID));
    }

    private List<UUID> activeHolders(String token) {
        return jdbc.queryForList("SELECT user_id FROM device_tokens WHERE token = ? AND active", UUID.class, token);
    }

    private long versionOf(UUID deviceTokenId) {
        return jdbc.queryForObject("SELECT version FROM device_tokens WHERE id = ?", Long.class, deviceTokenId);
    }

    /** Only this test takes advisory locks that can conflict, so any waiting one is the token lock. */
    private boolean awaitBlockedAdvisoryLock(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted", Long.class);
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static void awaitRelease(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the first registration was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String uniqueToken() {
        return "ExponentPushToken[it-" + UUID.randomUUID() + "]";
    }
}
