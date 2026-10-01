package com.parkio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.parkio.media.application.command.UploadMediaCommand;
import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.application.result.MediaUploadResult;
import com.parkio.media.domain.exception.MediaException;
import com.parkio.media.infrastructure.idempotency.IdempotencyService;
import com.parkio.media.infrastructure.idempotency.IdempotentResponse;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore;
import com.parkio.media.infrastructure.storage.MinioMediaStorageAdapter;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
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
 * U05 media erasure against uploads that race it, through the production upload transaction
 * ({@code IdempotencyService.execute} → {@code MediaApplicationService.upload}, as
 * {@code MediaController} calls it), real PostgreSQL (Flyway) and MinIO, synthetic images only.
 * The independent re-review reproduced a media SUCCESS next to a READY row, four validation rows
 * and a stored object by committing an upload between the worker's final check and its ACK; these
 * tests put an upload into that window, after the tombstone, and around the start of the erasure.
 * Whatever the interleaving, a queued SUCCESS must never coexist with media of the erased user.
 * Except for the per-owner fence test, the tests use only APIs that also exist before the fix, so
 * they run unchanged on both sides.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MediaUploadErasureRaceIT {

    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String BUCKET = "parkio-media-upload-race-it";
    private static final AtomicInteger IMAGES = new AtomicInteger();

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
        registry.add("parkio.media.storage.endpoint", MediaUploadErasureRaceIT::minioEndpoint);
        registry.add("parkio.media.storage.public-endpoint", () -> "http://localhost:" + MINIO.getMappedPort(9000));
        registry.add("parkio.media.storage.access-key", () -> ACCESS_KEY);
        registry.add("parkio.media.storage.secret-key", () -> SECRET_KEY);
        registry.add("parkio.media.storage.region", () -> "us-east-1");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
    }

    @Autowired private AccountErasureHandler handler;
    @Autowired private MediaObjectErasureWorker worker;
    @Autowired private MediaApplicationService uploads;
    @Autowired private IdempotencyService idempotency;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private MediaErasureJobStore jobs;
    @SpyBean private MinioMediaStorageAdapter storage;

    private final MinioClient minio = MediaErasureFixture.minioClient(minioEndpoint(), ACCESS_KEY, SECRET_KEY);
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @BeforeEach
    void bucket() throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    /**
     * The re-review interleaving: the worker has confirmed the namespace empty and its final
     * check found no row; an upload admitted before the erasure then runs through the production
     * transaction before the ACK decision commits.
     */
    @Test
    void uploadCommittedAfterTheFinalCheckNeverCoexistsWithSuccess() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        Instant now = Instant.now();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO erased_user_tombstones (auth_user_id, erased_at) VALUES (?, now())", owner);
            jobs.open(job, request, owner, now, now.plusSeconds(120));
        });
        CompletableFuture<MediaUploadResult> upload = new CompletableFuture<>();
        AtomicBoolean injected = new AtomicBoolean();
        doAnswer(invocation -> {
            Object count = invocation.callRealMethod();
            if (owner.equals(invocation.getArgument(0)) && !injected.getAndSet(true)) {
                // Give the upload up to five seconds; a correct fence keeps it waiting instead.
                pool.submit(() -> runUpload(owner, upload));
                try {
                    upload.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException | java.util.concurrent.ExecutionException notCommitted) {
                    // Either blocked by the fence or refused; the worker goes on with its decision.
                }
            }
            return count;
        }).when(jobs).countMedia(any());

        worker.process(job);
        Throwable uploadFailure = outcomeOf(upload);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(injected).as("upload injected after the final check").isTrue();
        assertNoMediaBesideSuccess(softly, owner, request);
        softly.assertThat(errorCode(uploadFailure)).as("upload of an erased owner refused").isEqualTo("ACCOUNT_ERASED");
        softly.assertAll();
    }

    @Test
    void uploadAfterTheTombstoneIsRefusedWithoutStoringAnything() throws Exception {
        UUID owner = UUID.randomUUID();
        jdbc.update("INSERT INTO erased_user_tombstones (auth_user_id, erased_at) VALUES (?, now())", owner);
        CompletableFuture<MediaUploadResult> upload = new CompletableFuture<>();

        runUpload(owner, upload);
        Throwable uploadFailure = outcomeOf(upload);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(errorCode(uploadFailure)).as("upload of an erased owner refused").isEqualTo("ACCOUNT_ERASED");
        softly.assertThat(count("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ?", owner)).as("media rows").isZero();
        softly.assertThat(namespaceVersions(owner)).as("stored objects").isEmpty();
        softly.assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE user_id = ?", owner))
                .as("idempotency records").isZero();
        softly.assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE strpos(payload, ?) > 0", owner.toString()))
                .as("outbox rows naming the owner").isZero();
        softly.assertAll();
    }

    /**
     * An upload passed its checks and is writing its object when the erasure request arrives: the
     * erasure must not start (no tombstone) until that upload has committed or rolled back, and
     * the committed media must be erased before SUCCESS.
     */
    @Test
    void uploadInFlightWhenTheErasureStartsIsErasedBeforeSuccess() throws Exception {
        UUID owner = UUID.randomUUID();
        String namespace = MediaApplicationService.objectKeyPrefix(owner);
        CountDownLatch stored = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (((String) invocation.getArgument(0)).startsWith(namespace)) {
                stored.countDown();
                release.await(60, TimeUnit.SECONDS);
            }
            return result;
        }).when(storage).store(anyString(), any(), anyString());
        CompletableFuture<MediaUploadResult> upload = new CompletableFuture<>();
        pool.submit(() -> runUpload(owner, upload));
        assertThat(stored.await(30, TimeUnit.SECONDS)).as("upload reached its object write").isTrue();

        UserErasureRequestedEvent event = new UserErasureRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), owner,
                Instant.now());
        CompletableFuture<Void> erasure = CompletableFuture.runAsync(() -> handler.handle(event), pool);
        Thread.sleep(2_000);
        long tombstonesWhileUploadInFlight = count(
                "SELECT COUNT(*) FROM erased_user_tombstones WHERE auth_user_id = ?", owner);
        release.countDown();
        Throwable uploadFailure = outcomeOf(upload);
        erasure.get(120, TimeUnit.SECONDS);
        for (int i = 0; i < 5 && ackRows(event.erasureRequestId()) == 0; i++) {
            worker.process(AccountErasureHandler.ackEventId(event));
        }

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(tombstonesWhileUploadInFlight).as("erasure waited for the in-flight upload").isZero();
        softly.assertThat(uploadFailure).as("upload admitted before the erasure completes").isNull();
        softly.assertThat(ackRows(event.erasureRequestId())).as("media SUCCESS").isEqualTo(1);
        assertNoMediaBesideSuccess(softly, owner, event.erasureRequestId());
        softly.assertAll();
    }

    /**
     * The fence is a PostgreSQL lock per owner: while an erasure transaction holds one owner's
     * fence, that owner's upload waits for it (visible as an ungranted advisory lock), while another
     * owner's upload is neither kept waiting nor refused. Uses the fixed API (not run before the fix).
     */
    @Test
    void anErasureFenceHoldsOnlyItsOwnersWrites() throws Exception {
        UUID erasing = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> fence = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jobs.holdOwner(erasing);
                    held.countDown();
                    try {
                        release.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }), pool);
        assertThat(held.await(30, TimeUnit.SECONDS)).as("erasure fence held").isTrue();
        CompletableFuture<MediaUploadResult> fencedUpload = new CompletableFuture<>();
        pool.submit(() -> runUpload(erasing, fencedUpload));
        boolean fencedUploadWaiting = awaitAdvisoryLockWaiters(1);

        CompletableFuture<MediaUploadResult> otherUpload = new CompletableFuture<>();
        runUpload(other, otherUpload);
        Throwable otherFailure = outcomeOf(otherUpload);
        boolean fencedUploadStillWaiting = !fencedUpload.isDone();
        release.countDown();
        fence.get(30, TimeUnit.SECONDS);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(fencedUploadWaiting).as("fenced owner's upload waits on the advisory lock").isTrue();
        softly.assertThat(otherFailure).as("other owner's upload while the fence is held").isNull();
        softly.assertThat(fencedUploadStillWaiting).as("fenced owner's upload still waiting meanwhile").isTrue();
        softly.assertThat(count("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ?", other))
                .as("other owner's media rows").isEqualTo(1);
        softly.assertThat(namespaceVersions(other)).as("other owner's stored objects").hasSize(1);
        // No tombstone was committed, so the waiting upload is admitted once the fence is released.
        softly.assertThat(outcomeOf(fencedUpload)).as("fenced upload after release").isNull();
        softly.assertAll();
    }

    private boolean awaitAdvisoryLockWaiters(int expected) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Long waiting = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND classid::bigint = ?",
                    Long.class, 0x4D454431L);
            if (waiting != null && waiting >= expected) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private void assertNoMediaBesideSuccess(SoftAssertions softly, UUID owner, UUID request) throws Exception {
        long acks = ackRows(request);
        softly.assertThat(acks).as("media SUCCESS queued").isEqualTo(1);
        softly.assertThat(count("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ?", owner))
                .as("media rows of the erased owner next to SUCCESS").isZero();
        softly.assertThat(count(
                "SELECT COUNT(*) FROM media_validation_results WHERE media_id IN (SELECT id FROM media_files WHERE owner_user_id = ?)",
                owner)).as("validation rows of the erased owner next to SUCCESS").isZero();
        softly.assertThat(namespaceVersions(owner)).as("stored object versions of the erased owner next to SUCCESS")
                .isEmpty();
    }

    private void runUpload(UUID owner, CompletableFuture<MediaUploadResult> outcome) {
        try {
            UploadMediaCommand command = new UploadMediaCommand(owner, "image/png", png());
            IdempotentResponse<MediaUploadResult> response = idempotency.execute(owner, "POST", "/api/v1/media/upload",
                    "race-" + UUID.randomUUID(), "race-fingerprint", MediaUploadResult.class,
                    () -> IdempotentResponse.first(201, uploads.upload(command)));
            outcome.complete(response.body());
        } catch (Throwable failure) {
            outcome.completeExceptionally(failure);
        }
    }

    private static Throwable outcomeOf(CompletableFuture<MediaUploadResult> upload) throws Exception {
        try {
            upload.get(120, TimeUnit.SECONDS);
            return null;
        } catch (java.util.concurrent.ExecutionException failure) {
            return failure.getCause();
        }
    }

    private static String errorCode(Throwable failure) {
        return failure instanceof MediaException media ? media.errorCode().name() : String.valueOf(failure);
    }

    private List<String> namespaceVersions(UUID owner) throws Exception {
        List<String> versions = new ArrayList<>();
        for (Result<Item> result : minio.listObjects(ListObjectsArgs.builder().bucket(BUCKET)
                .prefix(MediaApplicationService.objectKeyPrefix(owner)).includeVersions(true).recursive(true).build())) {
            versions.add(result.get().objectName());
        }
        return versions;
    }

    private long ackRows(UUID request) {
        return count("""
                SELECT COUNT(*) FROM outbox_events
                WHERE aggregate_type = 'AccountErasure' AND event_type = 'UserErasureAcknowledged' AND aggregate_id = ?
                """, request);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB);
        int n = IMAGES.incrementAndGet();
        image.setRGB(n % 12, (n / 12) % 12, 0x10_00_00 + n);
        image.setRGB(5, 5, UUID.randomUUID().hashCode());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
}
