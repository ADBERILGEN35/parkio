package com.parkio.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * H-05 (#546) on PostgreSQL with the Flyway schema: a push token is active for at most one account,
 * concurrent registrations of one token apply one after the other, and the erasure fence still
 * decides who may register. #601: a registration gives up on a lock after the bound, and the client
 * gets the generic 500 through the real HTTP path.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
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
    @Autowired private MockMvc mockMvc;
    @Value("${parkio.gateway.internal-secret}") private String gatewaySecret;

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

    /**
     * #601: a registration gives up on the token lock after the bound instead of waiting for its holder. The
     * client gets the generic 500, nothing of the registration is kept, and a later registration works.
     */
    @Test
    void registrationWaitingOnTheTokenLockGivesUpAfterTheBound(CapturedOutput output) throws Exception {
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
            MockHttpServletResponse answer = answerOf(pool.submit(() -> registerOverHttp(waiter, token)));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertGaveUpOnALock(answer, output);
            assertThat(waited).isGreaterThanOrEqualTo(LOCK_BOUND).isLessThan(LOCK_BOUND.plus(SLACK));
            release.countDown();
            holderTransaction.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(activeHolders(token)).containsExactly(holder);
        assertThat(rowsOf(waiter)).isZero();
        // The app registers again on its next start or sign-in; once the lock is free, that takes the token over.
        assertThat(registerOverHttp(waiter, token).getStatus()).isEqualTo(201);
        assertThat(activeHolders(token)).containsExactly(waiter);
    }

    /**
     * #601: the bound also ends a wait for another account's row lock, held here by an open transaction. The
     * handover is rolled back, so the previous account keeps the token and its pushes keep reaching the device.
     */
    @Test
    void handoverWaitingOnAnotherAccountsRowGivesUpAfterTheBound(CapturedOutput output) throws Exception {
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
            MockHttpServletResponse answer = answerOf(pool.submit(() -> registerOverHttp(next, token)));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertGaveUpOnALock(answer, output);
            assertThat(waited).isGreaterThanOrEqualTo(LOCK_BOUND).isLessThan(LOCK_BOUND.plus(SLACK));
            release.countDown();
            rowLock.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(activeHolders(token)).containsExactly(previous);
        assertThat(rowsOf(next)).isZero();
        assertThat(registerOverHttp(next, token).getStatus()).isEqualTo(201);
        assertThat(activeHolders(token)).containsExactly(next);
    }

    /** The answer to a registration that must give up; one still waiting after the bound and the slack fails. */
    private static MockHttpServletResponse answerOf(Future<MockHttpServletResponse> call) throws Exception {
        return call.get(LOCK_BOUND.plus(SLACK).toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * What a client sees when its registration gave up on a lock: the catch-all 500 of GlobalExceptionHandler,
     * with no database detail in the body. The cause, PostgreSQL's lock timeout, is only in the ERROR log.
     */
    private static void assertGaveUpOnALock(MockHttpServletResponse answer, CapturedOutput output) throws Exception {
        String body = answer.getContentAsString();
        assertThat(answer.getStatus()).as(body).isEqualTo(500);
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("INTERNAL_ERROR");
        assertThat((String) JsonPath.read(body, "$.message")).isEqualTo("An unexpected error occurred.");
        assertThat(body).doesNotContainIgnoringCase("lock").doesNotContainIgnoringCase("sql")
                .doesNotContain("55P03").doesNotContain("device_tokens");
        assertThat(output.getAll().lines()
                .anyMatch(line -> line.contains("ERROR") && line.contains("Unexpected error handling request")))
                .as("logged at ERROR").isTrue();
        assertThat(output.getAll()).contains("canceling statement due to lock timeout");
    }

    private MockHttpServletResponse registerOverHttp(UUID userId, String token) throws Exception {
        return mockMvc.perform(post("/api/v1/notifications/device-token")
                        .header("X-Gateway-Auth", gatewaySecret)
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"platform\":\"ANDROID\"}"))
                .andReturn()
                .getResponse();
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
