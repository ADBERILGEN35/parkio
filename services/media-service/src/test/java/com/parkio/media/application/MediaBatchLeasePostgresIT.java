package com.parkio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.media.application.port.ErasureAckOutbox;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
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
 * N-20 / #379: media erasure batch leasing on real PostgreSQL (Flyway), synthetic fixtures only.
 *
 * <p>Claiming a whole due batch at one timestamp lets a slow first job burn later jobs' leases
 * while they wait; a second worker can reclaim them and the original holder cannot finalize under
 * the stale token. Per-job leasing (list due ids, then {@code claim} immediately before each
 * attempt) removes that avoidable expiration without weakening completion ownership checks.
 *
 * <p>Slack already leases one row at a time; its crash-after-claim charge window is documented by
 * {@code scripts/slack_biz/run_reliability_acceptance.py} scenario 5 and the mid-send attempt
 * counters in {@code test_ambiguous_delivery.py} (no real Slack send). Delivery wording was
 * corrected so it no longer claims unsent rows are never charged.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MediaBatchLeasePostgresIT {

    private static final Duration LEASE = Duration.ofSeconds(120);
    private static final Duration BUDGET = Duration.ofSeconds(60);

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
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
    }

    @Autowired private MediaErasureJobStore jobs;
    @Autowired private ErasureAckOutbox ackOutbox;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));
    private MediaStoragePort storage;
    private MediaObjectErasureWorker worker;

    @BeforeEach
    void setUp() {
        storage = mock(MediaStoragePort.class);
        when(storage.versionsOf(anyString(), anyString())).thenReturn(List.of());
        when(storage.versionsUnder(anyString())).thenReturn(List.of());
        worker = new MediaObjectErasureWorker(
                jobs, storage, ackOutbox, transactionManager, clock, new SimpleMeterRegistry(),
                true, 20, LEASE.toMillis(), 5_000, 900_000, BUDGET.toMillis());
    }

    @Test
    void batchClaimExpiresWaitingJobWhilePerJobLeaseKeepsItValid() {
        UUID first = openJob();
        UUID second = openJob();
        Instant batchStart = clock.instant();
        Instant batchLeaseEnd = batchStart.plus(LEASE);

        // Reproduce the pre-fix defect with the real job store: claim both at one timestamp.
        List<MediaErasureJobStore.Claim> batch = jobs.claimDue(20, batchStart, batchLeaseEnd);
        assertThat(batch).extracting(MediaErasureJobStore.Claim::ackEventId).containsExactly(first, second);
        UUID staleSecondToken = batch.stream()
                .filter(c -> c.ackEventId().equals(second))
                .findFirst()
                .orElseThrow()
                .token();

        // First job consumes more than the batch lease while the second waits held.
        clock.advance(LEASE.plusSeconds(1));
        assertThat(lockClaim(second, staleSecondToken, clock.instant()))
                .as("waiting job's batch lease expired while the first job ran")
                .isFalse();

        // A second worker reclaims the later job; the original token cannot finalize.
        UUID reclaimed = jobs.claim(second, clock.instant(), clock.instant().plus(LEASE)).orElseThrow();
        assertThat(lockClaim(second, staleSecondToken, clock.instant()))
                .as("stale ownership after reclaim")
                .isFalse();
        assertThat(lockClaim(second, reclaimed, clock.instant()))
                .as("reclaimer holds a live claim")
                .isTrue();
        assertThat(jobs.release(second, reclaimed, clock.instant(), clock.instant())).isTrue();

        // Per-job leasing: list due ids, then claim immediately before each attempt.
        clock.advance(Duration.ofSeconds(1));
        Instant pollAt = clock.instant();
        assertThat(jobs.dueIds(20, pollAt)).contains(first, second);

        AtomicInteger firstCalls = new AtomicInteger();
        when(storage.versionsUnder(anyString())).thenAnswer(invocation -> {
            if (firstCalls.getAndIncrement() == 0) {
                // Burn more than the lease while the first attempt runs; the second is not leased yet.
                clock.advance(LEASE.plusSeconds(1));
            }
            return List.of();
        });

        worker.processDue();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(ackRows(first)).as("first job SUCCESS").isEqualTo(1);
        softly.assertThat(ackRows(second)).as("second job SUCCESS under fresh per-job lease").isEqualTo(1);
        softly.assertThat(jobRows(first)).as("first job deleted").isZero();
        softly.assertThat(jobRows(second)).as("second job deleted").isZero();
        softly.assertAll();
    }

    private UUID openJob() {
        UUID job = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Instant now = clock.instant();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO erased_user_tombstones (auth_user_id, erased_at) VALUES (?, now())", owner);
            jobs.open(job, UUID.randomUUID(), owner, now, now);
        });
        return job;
    }

    private boolean lockClaim(UUID jobId, UUID token, Instant now) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(
                status -> jobs.lockClaim(jobId, token, now)));
    }

    private long ackRows(UUID jobId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'AccountErasure' AND event_id = ?",
                Long.class, jobId);
        return n == null ? 0 : n;
    }

    private long jobRows(UUID jobId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM media_erasure_jobs WHERE ack_event_id = ?", Long.class, jobId);
        return n == null ? 0 : n;
    }
}
