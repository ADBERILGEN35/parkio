package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Autowired private jakarta.persistence.EntityManager entityManager;

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
        store.failNextPuts(1);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        erasure.handleAcknowledgement(ack(requestId, user.id()));
        assertThat(status(requestId)).isEqualTo("IN_PROGRESS");
        assertThat(recordingStatus(requestId)).isEqualTo("PENDING_DURABLE");

        store.putIfAbsent(
                com.parkio.auth.application.port.DurableErasureRecord.of(
                        requestId, user.id(), NOW));
        jdbc.update(
                """
                UPDATE erasure_requests
                SET durable_recording_status = 'DURABLY_RECORDED',
                    durable_retry_next_at = NULL,
                    durable_worker_claim_token = NULL,
                    durable_worker_claim_expires_at = NULL
                WHERE id = ?
                """,
                requestId);
        entityManager.clear();
        assertThat(recordingStatus(requestId)).isEqualTo("DURABLY_RECORDED");
        assertThat(status(requestId)).isEqualTo("IN_PROGRESS");

        worker.tick();
        assertThat(status(requestId)).isEqualTo("COMPLETE");
        assertThat(user.status()).isEqualTo(AuthUserStatus.ERASED);
        assertThat(store.lastFindSawActiveTransaction()).isFalse();
    }

    @Test
    void anonymizationFailureRollsBackCompleteAndRetryFinishesBoth() {
        AuthUser user = newUser();
        ErasureDurableWorkerClaim claim = claimedDurablyRecorded(user);
        when(passwordHasher.hash(any()))
                .thenThrow(new RuntimeException("anonymize failed"))
                .thenReturn("replacement-hash");

        assertThatThrownBy(() -> erasure.processWorkerReconcileClaim(claim))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("anonymize failed");
        assertThat(status(claim.requestId())).isEqualTo("IN_PROGRESS");
        assertThat(user.status()).isNotEqualTo(AuthUserStatus.ERASED);

        erasure.processWorkerReconcileClaim(claim);
        assertThat(status(claim.requestId())).isEqualTo("COMPLETE");
        assertThat(user.status()).isEqualTo(AuthUserStatus.ERASED);
    }

    @Test
    void expiredOrReclaimedClaimCannotCompleteRequest() {
        AuthUser user = newUser();
        ErasureDurableWorkerClaim claim = claimedDurablyRecorded(user);
        jdbc.update(
                """
                UPDATE erasure_requests
                SET durable_worker_claim_expires_at = ?
                WHERE id = ?
                """,
                Timestamp.from(NOW.minusSeconds(5)),
                claim.requestId());
        erasure.processWorkerReconcileClaim(claim);
        assertThat(status(claim.requestId())).isEqualTo("IN_PROGRESS");
        assertThat(user.status()).isNotEqualTo(AuthUserStatus.ERASED);

        UUID newer = UUID.randomUUID();
        List<ErasureDurableWorkerClaim> reclaimed =
                workerRepository.claimBatch(NOW, 1, NOW.plusSeconds(60), newer);
        assertThat(reclaimed).hasSize(1);
        erasure.processWorkerReconcileClaim(claim);
        assertThat(status(claim.requestId())).isEqualTo("IN_PROGRESS");

        erasure.processWorkerReconcileClaim(reclaimed.get(0));
        assertThat(status(claim.requestId())).isEqualTo("COMPLETE");
        assertThat(user.status()).isEqualTo(AuthUserStatus.ERASED);
    }

    private ErasureDurableWorkerClaim claimedDurablyRecorded(AuthUser user) {
        store.failNextPuts(1);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        erasure.handleAcknowledgement(ack(requestId, user.id()));
        store.putIfAbsent(
                com.parkio.auth.application.port.DurableErasureRecord.of(requestId, user.id(), NOW));
        jdbc.update(
                """
                UPDATE erasure_requests
                SET durable_recording_status = 'DURABLY_RECORDED',
                    durable_retry_next_at = NULL,
                    durable_worker_claim_token = NULL,
                    durable_worker_claim_expires_at = NULL
                WHERE id = ?
                """,
                requestId);
        entityManager.clear();
        List<ErasureDurableWorkerClaim> claims =
                workerRepository.claimBatch(NOW, 1, NOW.plusSeconds(60), UUID.randomUUID());
        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).requestId()).isEqualTo(requestId);
        return claims.get(0);
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
                workerRepository.claimBatch(now, 1, now.plusSeconds(60), token1);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).requestId()).isEqualTo(requestId);
        List<ErasureDurableWorkerClaim> second =
                workerRepository.claimBatch(now, 1, now.plusSeconds(60), token2);
        assertThat(second.stream().map(ErasureDurableWorkerClaim::requestId))
                .doesNotContain(requestId);

        workerRepository.releaseClaim(requestId, token1, NOW);
        List<ErasureDurableWorkerClaim> third =
                workerRepository.claimBatch(now, 1, now.plusSeconds(60), token2);
        assertThat(third.stream().map(ErasureDurableWorkerClaim::requestId)).contains(requestId);
    }

    @Test
    void expiredLeaseAllowsNewWorkerClaim() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        UUID token1 = UUID.randomUUID();
        workerRepository.claimBatch(NOW, 1, NOW.plusSeconds(1), token1);
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
                workerRepository.claimBatch(NOW, 1, NOW.plusSeconds(60), token2);
        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).claimToken()).isEqualTo(token2);
    }

    @Test
    void staleWorkerCannotMarkDurablyRecorded() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        UUID realToken = UUID.randomUUID();
        workerRepository.claimBatch(NOW, 1, NOW.plusSeconds(60), realToken);
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
                workerRepository.claimBatch(NOW, 1, NOW.plusSeconds(60), token);
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

    @Test
    void reconcileStoreFindRunsOutsideDatabaseTransaction() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        erasure.handleAcknowledgement(ack(requestId, user.id()));
        store.putIfAbsent(
                com.parkio.auth.application.port.DurableErasureRecord.of(
                        requestId, user.id(), NOW));
        jdbc.update(
                """
                UPDATE erasure_requests
                SET durable_recording_status = 'DURABLY_RECORDED',
                    durable_retry_next_at = NULL,
                    durable_worker_claim_token = NULL,
                    durable_worker_claim_expires_at = NULL
                WHERE id = ?
                """,
                requestId);
        entityManager.clear();
        store.clearFinds();
        worker.tick();
        assertThat(status(requestId)).isEqualTo("COMPLETE");
        assertThat(store.lastFindSawActiveTransaction()).isFalse();
    }

    @Test
    void failedRetryingWithMissingDurableRecordRemainsClaimable() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        erasure.handleAcknowledgement(failedAck(requestId, user.id()));
        assertThat(status(requestId)).isEqualTo("FAILED_RETRYING");
        assertThat(recordingStatus(requestId)).isEqualTo("PENDING_DURABLE");
        UUID probe = UUID.randomUUID();
        List<ErasureDurableWorkerClaim> claims =
                workerRepository.claimBatch(NOW, 1, NOW.plusSeconds(60), probe);
        assertThat(claims).extracting(ErasureDurableWorkerClaim::requestId).contains(requestId);
        workerRepository.releaseClaim(requestId, probe, NOW);
        worker.tick();
        assertThat(recordingStatus(requestId)).isEqualTo("DURABLY_RECORDED");
        assertThat(status(requestId)).isEqualTo("FAILED_RETRYING");
    }

    @Test
    void participantFailedThenSuccessCompletesAfterDurableRecord() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        erasure.handleAcknowledgement(failedAck(requestId, user.id()));
        assertThat(status(requestId)).isEqualTo("FAILED_RETRYING");
        worker.tick();
        assertThat(recordingStatus(requestId)).isEqualTo("DURABLY_RECORDED");
        when(inbox.tryClaim(any(), any(), any())).thenReturn(true);
        erasure.handleAcknowledgement(ack(requestId, user.id()));
        assertThat(status(requestId)).isEqualTo("COMPLETE");
        assertThat(store.lastFindSawActiveTransaction()).isFalse();
    }

    @Test
    void expiredTokenCannotRecordRetryOrChangeComplete() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        UUID token = UUID.randomUUID();
        workerRepository.claimBatch(NOW, 1, NOW.plusSeconds(60), token);
        jdbc.update(
                """
                UPDATE erasure_requests
                SET durable_worker_claim_expires_at = ?
                WHERE id = ?
                """,
                Timestamp.from(NOW.minusSeconds(5)),
                requestId);
        int updated = workerRepository.recordRetryScheduled(
                requestId,
                token,
                NOW,
                99,
                NOW.plusSeconds(30),
                "STALE");
        assertThat(updated).isZero();
        assertThat(retryAttempts(requestId)).isLessThan(99);

        erasure.persistDurableRecord(requestId);
        erasure.handleAcknowledgement(ack(requestId, user.id()));
        assertThat(status(requestId)).isEqualTo("COMPLETE");
        int againstComplete = workerRepository.recordRetryScheduled(
                requestId, token, NOW, 100, NOW.plusSeconds(30), "STALE");
        assertThat(againstComplete).isZero();
        assertThat(status(requestId)).isEqualTo("COMPLETE");
    }

    @Test
    void recoveryContinuesBeyondPreviousAttemptLimitAndReconcilesAfterManualPersist() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        jdbc.update(
                """
                UPDATE erasure_requests
                SET durable_retry_attempt_count = 50,
                    durable_retry_next_at = ?,
                    last_error_code = 'DURABLE_PERSIST_RETRY_EXHAUSTED'
                WHERE id = ?
                """,
                Timestamp.from(NOW.minusSeconds(1)),
                requestId);
        List<ErasureDurableWorkerClaim> claims =
                workerRepository.claimBatch(NOW, 1, NOW.plusSeconds(60), UUID.randomUUID());
        assertThat(claims).extracting(ErasureDurableWorkerClaim::requestId).contains(requestId);

        erasure.persistDurableRecord(requestId);
        assertThat(recordingStatus(requestId)).isEqualTo("DURABLY_RECORDED");
        Instant next = retryNextAt(requestId);
        assertThat(next).isNull();
        erasure.handleAcknowledgement(ack(requestId, user.id()));
        assertThat(status(requestId)).isEqualTo("COMPLETE");
    }

    @Test
    void staleJpaEntityCannotOverwriteAfterJdbcClaimBumpsVersion() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        var stale = requests.findById(requestId).orElseThrow();
        Long versionBefore = jdbc.queryForObject(
                "SELECT version FROM erasure_requests WHERE id = ?", Long.class, requestId);
        workerRepository.claimBatch(NOW, 1, NOW.plusSeconds(60), UUID.randomUUID());
        Long versionAfter = jdbc.queryForObject(
                "SELECT version FROM erasure_requests WHERE id = ?", Long.class, requestId);
        assertThat(versionAfter).isEqualTo(versionBefore + 1);
        stale.markDurablyRecorded();
        assertThatThrownBy(() -> {
                    requests.saveAndFlush(stale);
                })
                .isInstanceOfAny(
                        org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                        jakarta.persistence.OptimisticLockException.class);
        assertThat(recordingStatus(requestId)).isEqualTo("PENDING_DURABLE");
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

    private UserErasureAcknowledgedEvent failedAck(UUID requestId, UUID userId) {
        return new UserErasureAcknowledgedEvent(
                UUID.randomUUID(), requestId, userId, "user", "FAILED", NOW);
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
