package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.InboxEventRepository;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.application.port.PasswordHasher;
import com.parkio.auth.application.port.PasswordResetRepository;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.application.support.InMemoryDurableErasureRecordStore;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.EmailLocale;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.auth.infrastructure.lifecycle.ErasureDurableRecordingWorker;
import com.parkio.auth.infrastructure.persistence.ErasureDurableWorkerRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasureRequestJpaRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasureServiceAckJpaRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Import(AccountErasureDurableRecordingWorkerPostgresIT.Config.class)
class AccountErasureDurableRecordingWorkerPostgresIT {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final String PASSWORD = "pw";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_auth_durable_worker_it")
                    .withUsername("parkio")
                    .withPassword("parkio");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        registry.add("management.tracing.enabled", () -> "false");
        registry.add("parkio.privacy.account-erasure.enabled", () -> "true");
        registry.add("parkio.privacy.account-erasure.durable-recording-enabled", () -> "true");
        registry.add("parkio.privacy.account-erasure.durable-recording-retry-worker-enabled", () -> "true");
        registry.add("parkio.privacy.account-erasure.durable-recording-retry-worker-batch-size", () -> "5");
        registry.add("parkio.privacy.account-erasure.durable-recording-retry-worker-max-attempts", () -> "5");
        registry.add("parkio.privacy.account-erasure.durable-recording-retry-worker-base-backoff-ms", () -> "1000");
        registry.add("parkio.privacy.account-erasure.durable-recording-retry-worker-lease-ms", () -> "60000");
        registry.add("parkio.privacy.account-erasure.participants", () -> "user");
    }

    @TestConfiguration
    static class Config {
        @Bean
        InMemoryDurableErasureRecordStore durableErasureRecordStore() {
            return new InMemoryDurableErasureRecordStore();
        }

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @MockBean private AuthUserRepository users;
    @MockBean private RefreshTokenRepository refreshTokens;
    @MockBean private PasswordResetRepository passwordResets;
    @MockBean private PasswordHasher passwordHasher;
    @MockBean private OutboxEventAppender outbox;
    @MockBean private InboxEventRepository inbox;
    @MockBean private LoginFailureTracker loginFailureTracker;
    @MockBean private EmailVerificationSender emailVerificationSender;

    @Autowired private AccountErasureApplicationService erasure;
    @Autowired private ErasureDurableRecordingWorker worker;
    @Autowired private ErasureDurableWorkerRepository workerRepository;
    @Autowired private InMemoryDurableErasureRecordStore store;
    @Autowired private ErasureRequestJpaRepository requests;
    @Autowired private ErasureServiceAckJpaRepository acks;
    @Autowired private ErasedUserTombstoneJpaRepository tombstones;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        acks.deleteAll();
        requests.deleteAll();
        tombstones.deleteAll();
        store.clear();
        when(users.save(any(AuthUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordHasher.matches(eq(PASSWORD), eq("hash"))).thenReturn(true);
        when(passwordHasher.hash(any())).thenReturn("replacement-hash");
        when(inbox.tryClaim(any(), any(), any())).thenReturn(true);
    }

    @Test
    void failedPutThenWorkerRetryAfterRestartCompletes() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        var accepted = erasure.requestDeletion(user.id(), PASSWORD);
        UUID requestId = accepted.erasureRequestId();
        assertThat(recordingStatus(requestId)).isEqualTo("PENDING_DURABLE");
        assertThat(retryNextAt(requestId)).isNotNull();
        assertThat(store.size()).isZero();

        worker.tick();
        assertThat(recordingStatus(requestId)).isEqualTo("DURABLY_RECORDED");
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.lastPutSawActiveTransaction()).isFalse();
    }

    @Test
    void allAcksBeforeWorkerRetryCompletesOnTick() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        erasure.handleAcknowledgement(ack(requestId, user.id()));
        assertThat(status(requestId)).isEqualTo("IN_PROGRESS");

        worker.tick();
        assertThat(status(requestId)).isEqualTo("COMPLETE");
        assertThat(user.status()).isEqualTo(AuthUserStatus.ERASED);
    }

    @Test
    void durablyRecordedReconciliationCompletesWhenAcksPresent() {
        AuthUser user = newUser();
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        erasure.handleAcknowledgement(ack(requestId, user.id()));
        assertThat(status(requestId)).isEqualTo("COMPLETE");

        jdbc.update(
                """
                UPDATE erasure_requests
                SET status = 'IN_PROGRESS', completed_at = NULL
                WHERE id = ?
                """,
                requestId);
        assertThat(status(requestId)).isEqualTo("IN_PROGRESS");
        assertThat(recordingStatus(requestId)).isEqualTo("DURABLY_RECORDED");

        worker.tick();
        assertThat(status(requestId)).isEqualTo("COMPLETE");
    }

    @Test
    void crashAfterStorePutBeforeDatabaseUpdateIsRecoveredByWorker() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        var row = requests.findById(requestId).orElseThrow();
        store.putIfAbsent(
                com.parkio.auth.application.port.DurableErasureRecord.of(
                        row.getId(), row.getAuthUserId(), row.getRequestedAt()));
        assertThat(recordingStatus(requestId)).isEqualTo("PENDING_DURABLE");
        assertThat(store.size()).isEqualTo(1);

        worker.tick();
        assertThat(recordingStatus(requestId)).isEqualTo("DURABLY_RECORDED");
    }

    @Test
    void concurrentWorkersDoNotClaimSameRowWhileLeaseActive() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        assertThat(requests.count()).isOne();
        Instant now = NOW;
        UUID token1 = UUID.randomUUID();
        UUID token2 = UUID.randomUUID();
        List<ErasureDurableWorkerClaim> first =
                workerRepository.claimBatch(now, 1, 5, now.plusSeconds(60), token1);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).requestId()).isEqualTo(requestId);
        List<ErasureDurableWorkerClaim> second =
                workerRepository.claimBatch(now, 1, 5, now.plusSeconds(60), token2);
        assertThat(second.stream().map(ErasureDurableWorkerClaim::requestId))
                .doesNotContain(requestId);

        workerRepository.releaseClaim(requestId, token1, NOW);
        List<ErasureDurableWorkerClaim> third =
                workerRepository.claimBatch(now, 1, 5, now.plusSeconds(60), token2);
        assertThat(third.stream().map(ErasureDurableWorkerClaim::requestId)).contains(requestId);
    }

    @Test
    void expiredLeaseAllowsNewWorkerClaim() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        UUID token1 = UUID.randomUUID();
        workerRepository.claimBatch(NOW, 1, 5, NOW.plusSeconds(1), token1);
        jdbc.update(
                """
                UPDATE erasure_requests
                SET durable_worker_claim_expires_at = ?
                WHERE id = ?
                """,
                Timestamp.from(NOW.minusSeconds(30)),
                requestId);
        UUID token2 = UUID.randomUUID();
        List<ErasureDurableWorkerClaim> reclaimed =
                workerRepository.claimBatch(NOW, 1, 5, NOW.plusSeconds(60), token2);
        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).claimToken()).isEqualTo(token2);
    }

    @Test
    void staleWorkerCannotMarkDurablyRecorded() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        UUID realToken = UUID.randomUUID();
        workerRepository.claimBatch(NOW, 1, 5, NOW.plusSeconds(60), realToken);
        int stale = workerRepository.markDurablyRecordedIfClaimed(requestId, UUID.randomUUID(), NOW);
        assertThat(stale).isZero();
        assertThat(recordingStatus(requestId)).isEqualTo("PENDING_DURABLE");
    }

    @Test
    void retryBackoffIncrementsAttemptCount() {
        AuthUser user = newUser();
        store.failNextPuts(10);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        int attemptsBeforeWorker = retryAttempts(requestId);
        UUID token = UUID.randomUUID();
        List<ErasureDurableWorkerClaim> claims =
                workerRepository.claimBatch(NOW, 1, 5, NOW.plusSeconds(60), token);
        erasure.processWorkerPersistClaim(
                claims.get(0),
                5,
                java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(8));
        assertThat(retryAttempts(requestId)).isEqualTo(attemptsBeforeWorker + 1);
        assertThat(retryNextAt(requestId)).isAfter(NOW);
    }

    @Test
    void completeErasureIsNotReclaimedByWorker() {
        AuthUser user = newUser();
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        erasure.handleAcknowledgement(ack(requestId, user.id()));
        assertThat(status(requestId)).isEqualTo("COMPLETE");
        worker.tick();
        assertThat(status(requestId)).isEqualTo("COMPLETE");
    }

    @Test
    void parallelTicksUseSkipLockedClaims() throws Exception {
        AuthUser user1 = newUser();
        AuthUser user2 = newUser();
        store.failNextPuts(2);
        UUID id1 = erasure.requestDeletion(user1.id(), PASSWORD).erasureRequestId();
        UUID id2 = erasure.requestDeletion(user2.id(), PASSWORD).erasureRequestId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Runnable run = () -> {
            try {
                start.await();
                worker.tick();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };
        pool.submit(run);
        pool.submit(run);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(recordingStatus(id1)).isEqualTo("DURABLY_RECORDED");
        assertThat(recordingStatus(id2)).isEqualTo("DURABLY_RECORDED");
    }

    private AuthUser newUser() {
        AuthUser user = AuthUser.register(
                "rider-" + UUID.randomUUID() + "@example.com",
                "hash",
                "vhash",
                NOW.plusSeconds(3600),
                NOW,
                EmailLocale.TR,
                Set.of(new Role(UUID.randomUUID(), RoleName.USER)),
                NOW);
        user.verifyEmail(NOW);
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        return user;
    }

    private UserErasureAcknowledgedEvent ack(UUID requestId, UUID userId) {
        return new UserErasureAcknowledgedEvent(
                UUID.randomUUID(), requestId, userId, "user", "SUCCESS", NOW);
    }

    private String status(UUID requestId) {
        return requests.findById(requestId).orElseThrow().getStatus();
    }

    private String recordingStatus(UUID requestId) {
        return jdbc.queryForObject(
                "SELECT durable_recording_status FROM erasure_requests WHERE id = ?",
                String.class,
                requestId);
    }

    private Instant retryNextAt(UUID requestId) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT durable_retry_next_at FROM erasure_requests WHERE id = ?",
                Timestamp.class,
                requestId);
        return ts == null ? null : ts.toInstant();
    }

    private int retryAttempts(UUID requestId) {
        Integer count = jdbc.queryForObject(
                "SELECT durable_retry_attempt_count FROM erasure_requests WHERE id = ?",
                Integer.class,
                requestId);
        return count == null ? 0 : count;
    }
}
