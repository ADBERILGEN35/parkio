package com.parkio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.parkio.media.application.port.ErasureAckOutbox;
import com.parkio.media.application.port.MediaValidationResultRepository;
import com.parkio.media.application.port.OutboxEventAppender;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore.Claim;
import com.parkio.media.infrastructure.persistence.jpa.MediaFileJpaRepository;
import com.parkio.media.infrastructure.storage.MinioMediaStorageAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketVersioningArgs;
import io.minio.messages.VersioningConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * N-20: a batch lease that starts every due job at one timestamp lets later claims expire while an
 * earlier job still runs. The scheduled poll therefore lists due ids and claims each job only when
 * that attempt starts. Completion already refuses a stale or reclaimed claim; this IT proves the
 * avoidable expiry path and that per-job leasing removes it (real PostgreSQL + Testcontainers MinIO,
 * synthetic owners with empty namespaces only).
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MediaBatchLeasePostgresIT {

    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String BUCKET = "parkio-media-batch-lease-it";
    private static final Duration BUDGET = Duration.ofSeconds(60);
    private static final Duration LEASE = Duration.ofSeconds(120);
    private static final Duration FIRST_JOB_WORK = Duration.ofSeconds(90);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse(
                    // RELEASE.2024-09-13T20-26-02Z; same publisher digest as CI Compose.
                    "ghcr.io/adberilgen35/parkio/minio@sha256:efba309ba4dc89e48f37304db52a0b854c0e701ba944ca02205c4e292c1a756c"))
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.media.storage.bucket", () -> BUCKET);
        registry.add("parkio.media.storage.endpoint", MediaBatchLeasePostgresIT::minioEndpoint);
        registry.add("parkio.media.storage.public-endpoint", () -> "http://localhost:" + MINIO.getMappedPort(9000));
        registry.add("parkio.media.storage.access-key", () -> ACCESS_KEY);
        registry.add("parkio.media.storage.secret-key", () -> SECRET_KEY);
        registry.add("parkio.media.storage.region", () -> "us-east-1");
        registry.add("parkio.media.erasure-worker.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
    }

    @Autowired private MediaErasureJobStore jobs;
    @Autowired private ErasureAckOutbox ackOutbox;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MediaFileJpaRepository mediaFiles;
    @Autowired private MediaValidationResultRepository validationResults;
    @Autowired private OutboxEventAppender mediaEvents;
    @MockitoSpyBean private MinioMediaStorageAdapter storage;

    private final MinioClient minio = MediaErasureFixture.minioClient(minioEndpoint(), ACCESS_KEY, SECRET_KEY);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));
    private MediaErasureFixture fixture;
    private MediaObjectErasureWorker worker;

    @BeforeAll
    static void versionedBucket() throws Exception {
        MinioClient admin = MediaErasureFixture.minioClient(minioEndpoint(), ACCESS_KEY, SECRET_KEY);
        admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET)
                .config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED, null)).build());
    }

    @BeforeEach
    void setUp() {
        fixture = new MediaErasureFixture(minio, jdbc, transactionManager, mediaFiles, validationResults, mediaEvents);
        worker = new MediaObjectErasureWorker(jobs, storage, ackOutbox, transactionManager, clock,
                new SimpleMeterRegistry(), true, 20, LEASE.toMillis(), 5_000, 900_000, BUDGET.toMillis());
    }

    /**
     * Defect path on the batch-claim API: both jobs share one lease start; after the first job
     * burns the window a second worker reclaims the later job and the original token cannot finalize.
     */
    @Test
    void batchClaimedLaterJobExpiresWhileEarlierJobRunsAndStaleOwnerCannotFinalize() {
        UUID job1 = phaseOneCommitted(UUID.randomUUID());
        UUID job2 = phaseOneCommitted(UUID.randomUUID());
        Instant claimedAt = clock.instant();
        List<Claim> batch = jobs.claimDue(20, claimedAt, claimedAt.plus(LEASE));
        assertThat(batch).extracting(Claim::ackEventId).containsExactlyInAnyOrder(job1, job2);
        UUID staleJob2Token = batch.stream()
                .filter(c -> c.ackEventId().equals(job2))
                .findFirst()
                .orElseThrow()
                .token();

        clock.advance(LEASE.plusSeconds(1));
        List<Claim> reclaimed = jobs.claimDue(20, clock.instant(), clock.instant().plus(LEASE));
        assertThat(reclaimed).extracting(Claim::ackEventId).contains(job2);
        assertThat(jobs.lockClaim(job2, staleJob2Token, clock.instant()))
                .as("stale batch token after reclaim")
                .isFalse();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(ackRows(job2)).as("SUCCESS under stale ownership").isZero();
        softly.assertThat(jobRows(job2)).as("reclaimed job still pending").isEqualTo(1);
        softly.assertAll();
    }

    /**
     * Per-job leasing: while the first due job runs (and the clock advances), the later job stays
     * unclaimed so its lease cannot expire from queue wait; processDue then claims it fresh and
     * both attempts finalize.
     */
    @Test
    void processDueClaimsEachJobImmediatelyBeforeItsAttemptSoPeersDoNotExpireInQueue() {
        UUID owner1 = UUID.randomUUID();
        UUID owner2 = UUID.randomUUID();
        UUID job1 = phaseOneCommitted(owner1);
        UUID job2 = phaseOneCommitted(owner2);
        String ns1 = MediaApplicationService.objectKeyPrefix(owner1);

        AtomicBoolean firstTouch = new AtomicBoolean(true);
        AtomicReference<UUID> peerTokenDuringFirst = new AtomicReference<>();
        doAnswer(invocation -> {
            if (firstTouch.getAndSet(false)) {
                String namespace = invocation.getArgument(0);
                UUID peer = namespace.equals(ns1) ? job2 : job1;
                peerTokenDuringFirst.set(claimToken(peer));
                clock.advance(FIRST_JOB_WORK);
            }
            return List.of();
        }).when(storage).versionsUnder(anyString());

        worker.processDue();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(peerTokenDuringFirst.get())
                .as("peer due job must still be unclaimed while the earlier attempt runs")
                .isNull();
        softly.assertThat(ackRows(job1)).as("first job SUCCESS").isEqualTo(1);
        softly.assertThat(ackRows(job2)).as("second job SUCCESS with a fresh per-attempt lease").isEqualTo(1);
        softly.assertThat(jobRows(job1)).isZero();
        softly.assertThat(jobRows(job2)).isZero();
        softly.assertAll();
    }

    private UUID phaseOneCommitted(UUID owner) {
        UUID job = UUID.randomUUID();
        Instant now = clock.instant();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO erased_user_tombstones (auth_user_id, erased_at) VALUES (?, now())", owner);
            jobs.open(job, UUID.randomUUID(), owner, now, now);
        });
        return job;
    }

    private UUID claimToken(UUID jobId) {
        List<UUID> tokens = jdbc.queryForList(
                "SELECT claim_token FROM media_erasure_jobs WHERE ack_event_id = ?", UUID.class, jobId);
        return tokens.isEmpty() ? null : tokens.get(0);
    }

    private long ackRows(UUID job) {
        return fixture.count(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'AccountErasure' AND event_id = ?",
                job);
    }

    private long jobRows(UUID job) {
        return fixture.count("SELECT COUNT(*) FROM media_erasure_jobs WHERE ack_event_id = ?", job);
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
}
