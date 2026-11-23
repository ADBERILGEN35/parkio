package com.parkio.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.notification.application.command.RegisterDeviceTokenCommand;
import com.parkio.notification.application.event.UserErasureRequestedEvent;
import com.parkio.notification.domain.DevicePlatform;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.domain.exception.NotificationException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
