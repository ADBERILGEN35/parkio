package com.parkio.media.application;

import static com.parkio.media.application.MediaErasureFixture.randomContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.parkio.media.application.MediaErasureFixture.Seeded;
import com.parkio.media.application.port.ErasureAckOutbox;
import com.parkio.media.application.port.MediaStoragePort.StoredVersion;
import com.parkio.media.application.port.MediaValidationResultRepository;
import com.parkio.media.application.port.OutboxEventAppender;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore;
import com.parkio.media.infrastructure.persistence.jpa.MediaFileJpaRepository;
import com.parkio.media.infrastructure.storage.MinioMediaStorageAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.SetBucketVersioningArgs;
import io.minio.messages.Item;
import io.minio.messages.VersioningConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
 * U05 media erasure attempt bounds on a versioned bucket, real PostgreSQL (Flyway) and MinIO,
 * synthetic objects only. A worker with a test clock processes an erasure whose phase 1 already
 * committed; every version delete advances the clock by ten seconds, so the default 60 s attempt
 * budget is reached deterministically inside one key's versions or inside the orphan namespace.
 * An attempt must stop there (durable progress, no SUCCESS, job due again), and an attempt whose
 * claim expired, or was taken over by another instance's poll, must never queue the ACK. Only APIs
 * that exist before the fix are used, so the tests run unchanged on both sides.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MediaErasureBoundedAttemptIT {

    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String BUCKET = "parkio-media-bounded-it";
    private static final Duration BUDGET = Duration.ofSeconds(60);
    private static final Duration PER_DELETE = Duration.ofSeconds(10);
    private static final Duration LEASE = Duration.ofSeconds(120);

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
        registry.add("parkio.media.storage.endpoint", MediaErasureBoundedAttemptIT::minioEndpoint);
        registry.add("parkio.media.storage.public-endpoint", () -> "http://localhost:" + MINIO.getMappedPort(9000));
        registry.add("parkio.media.storage.access-key", () -> ACCESS_KEY);
        registry.add("parkio.media.storage.secret-key", () -> SECRET_KEY);
        registry.add("parkio.media.storage.region", () -> "us-east-1");
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
    @SpyBean private MinioMediaStorageAdapter storage;

    private final MinioClient minio = MediaErasureFixture.minioClient(minioEndpoint(), ACCESS_KEY, SECRET_KEY);
    private final MutableClock clock = new MutableClock(Instant.now());
    private MediaErasureFixture fixture;
    private MediaObjectErasureWorker bounded;

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
        bounded = new MediaObjectErasureWorker(jobs, storage, ackOutbox, transactionManager, clock,
                new SimpleMeterRegistry(), true, 20, LEASE.toMillis(), 5_000, 900_000, BUDGET.toMillis());
    }

    @Test
    void attemptStopsAtItsBudgetInsideOneKeysVersionsAndKeepsDurableProgress() throws Exception {
        UUID owner = UUID.randomUUID();
        Seeded media = fixture.upload(owner, BUCKET);
        for (int i = 0; i < 11; i++) {
            fixture.putObject(BUCKET, media.key(), randomContent());
        }
        assertThat(versionsUnder(MediaApplicationService.objectKeyPrefix(owner))).isEqualTo(12);
        slowVersionDeletes(owner);
        UUID job = phaseOneCommitted(owner);
        Instant start = clock.instant();

        bounded.process(job);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(Duration.between(start, clock.instant())).as("first attempt length")
                .isLessThanOrEqualTo(BUDGET.plus(PER_DELETE));
        softly.assertThat(ackRows(job)).as("SUCCESS after a stopped attempt").isZero();
        softly.assertThat(jobRows(job)).as("job still pending").isEqualTo(1);
        long left = versionsUnder(MediaApplicationService.objectKeyPrefix(owner));
        softly.assertThat(left).as("versions left after the first attempt").isBetween(1L, 11L);
        softly.assertAll();

        finish(job);
        assertThat(versionsUnder(MediaApplicationService.objectKeyPrefix(owner))).isZero();
        assertThat(fixture.count("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ?", owner)).isZero();
        assertThat(ackRows(job)).isEqualTo(1);
    }

    @Test
    void attemptStopsAtItsBudgetInsideTheOrphanNamespace() throws Exception {
        UUID owner = UUID.randomUUID();
        String namespace = MediaApplicationService.objectKeyPrefix(owner);
        for (int i = 0; i < 15; i++) {
            fixture.putObject(BUCKET, namespace + UUID.randomUUID() + ".png", randomContent());
        }
        slowVersionDeletes(owner);
        UUID job = phaseOneCommitted(owner);
        Instant start = clock.instant();

        bounded.process(job);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(Duration.between(start, clock.instant())).as("first attempt length")
                .isLessThanOrEqualTo(BUDGET.plus(PER_DELETE));
        softly.assertThat(ackRows(job)).as("SUCCESS after a stopped attempt").isZero();
        softly.assertThat(jobRows(job)).as("job still pending").isEqualTo(1);
        softly.assertThat(versionsUnder(namespace)).as("orphans left after the first attempt").isBetween(1L, 14L);
        softly.assertAll();

        finish(job);
        assertThat(versionsUnder(namespace)).isZero();
        assertThat(ackRows(job)).isEqualTo(1);
    }

    @Test
    void attemptWhoseClaimExpiredNeverQueuesTheAck() throws Exception {
        UUID owner = UUID.randomUUID();
        String namespace = MediaApplicationService.objectKeyPrefix(owner);
        fixture.upload(owner, BUCKET);
        AtomicBoolean expire = new AtomicBoolean(true);
        // The last storage call of the attempt (the empty namespace listing) outlives the lease.
        doAnswer(invocation -> {
            Object listed = invocation.callRealMethod();
            if (namespace.equals(invocation.getArgument(0)) && ((List<?>) listed).isEmpty() && expire.getAndSet(false)) {
                clock.advance(LEASE.plusSeconds(180));
            }
            return listed;
        }).when(storage).versionsUnder(anyString());
        UUID job = phaseOneCommitted(owner);

        bounded.process(job);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(expire).as("lease outlived during the attempt").isFalse();
        softly.assertThat(ackRows(job)).as("SUCCESS from an expired claim").isZero();
        softly.assertThat(jobRows(job)).as("job still pending").isEqualTo(1);
        softly.assertAll();

        finish(job);
        assertThat(ackRows(job)).isEqualTo(1);
        assertThat(jobRows(job)).isZero();
    }

    @Test
    void attemptWhoseJobWasReclaimedNeverQueuesTheAck() throws Exception {
        UUID owner = UUID.randomUUID();
        String namespace = MediaApplicationService.objectKeyPrefix(owner);
        fixture.upload(owner, BUCKET);
        AtomicBoolean reclaim = new AtomicBoolean(true);
        AtomicInteger reclaimed = new AtomicInteger();
        // While the attempt's last storage call is in flight its lease runs out and another
        // instance's poll claims the job; that claimant has not finished when the attempt returns.
        doAnswer(invocation -> {
            Object listed = invocation.callRealMethod();
            if (namespace.equals(invocation.getArgument(0)) && ((List<?>) listed).isEmpty() && reclaim.getAndSet(false)) {
                clock.advance(LEASE.plusSeconds(1));
                Instant now = clock.instant();
                reclaimed.set(jobs.claimDue(20, now, now.plus(LEASE)).size());
            }
            return listed;
        }).when(storage).versionsUnder(anyString());
        UUID job = phaseOneCommitted(owner);

        bounded.process(job);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(reclaimed.get()).as("job reclaimed during the attempt").isGreaterThanOrEqualTo(1);
        softly.assertThat(ackRows(job)).as("SUCCESS from a reclaimed attempt").isZero();
        softly.assertThat(jobRows(job)).as("job still pending for its new claim").isEqualTo(1);
        softly.assertAll();

        // The new claimant never finishes; once its lease has expired too, the poll completes the job once.
        clock.advance(LEASE.plusSeconds(1));
        bounded.processDue();
        assertThat(ackRows(job)).isEqualTo(1);
        assertThat(jobRows(job)).isZero();
    }

    /** Every delete of this owner's object versions takes ten seconds of the test clock. */
    private void slowVersionDeletes(UUID owner) {
        String namespace = MediaApplicationService.objectKeyPrefix(owner);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (((StoredVersion) invocation.getArgument(0)).objectKey().startsWith(namespace)) {
                clock.advance(PER_DELETE);
            }
            return null;
        }).when(storage).removeVersion(any());
    }

    /** Phase 1 as the handler commits it: tombstone, hidden media, a pending job; no attempt yet. */
    private UUID phaseOneCommitted(UUID owner) {
        UUID job = UUID.randomUUID();
        Instant now = clock.instant();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO erased_user_tombstones (auth_user_id, erased_at) VALUES (?, now())", owner);
            jdbc.update("UPDATE media_files SET status = 'DELETED', deleted_at = now() WHERE owner_user_id = ?", owner);
            jobs.open(job, UUID.randomUUID(), owner, now, now);
        });
        return job;
    }

    /** Later attempts (each on a fresh budget) finish the job. */
    private void finish(UUID job) {
        for (int i = 0; i < 20 && jobRows(job) > 0; i++) {
            clock.advance(Duration.ofSeconds(1));
            bounded.process(job);
        }
    }

    private long versionsUnder(String prefix) throws Exception {
        long n = 0;
        for (Result<Item> result : minio.listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(prefix)
                .includeVersions(true).recursive(true).build())) {
            result.get();
            n++;
        }
        return n;
    }

    private long ackRows(UUID job) {
        return fixture.count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'AccountErasure' AND event_id = ?",
                job);
    }

    private long jobRows(UUID job) {
        return fixture.count("SELECT COUNT(*) FROM media_erasure_jobs WHERE ack_event_id = ?", job);
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
}
