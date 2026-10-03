package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.ErasureCheckpointProducer;
import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.RecoveryVerdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.Verdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedPending;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.application.port.CapturedErasureLedger;
import com.parkio.auth.application.port.DurableErasureCheckpoint;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.parkio.auth.application.port.ErasureLedgerCapture;
import com.parkio.auth.infrastructure.persistence.JdbcErasureLedgerCapture;
import com.parkio.auth.infrastructure.persistence.PostgresDatabaseIdentity;
import io.minio.MinioClient;
import io.minio.messages.RetentionMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * U02 checkpoints (contract stage 2) on real PostgreSQL (Flyway schema) and a disposable MinIO
 * object-lock bucket: the producer wired by its flag, the SHARE-lock capture waiting for an
 * in-flight INSERT, a lock timeout publishing nothing, publication only after the capture
 * committed, and the latest checkpoint plus the records above it covering every tombstone even
 * when an erasure commits between the capture and the checkpoint's reservation. Synthetic data.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ErasureCheckpointPostgresMinioIT {

    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String CONTEXT_BUCKET = "parkio-erasure-checkpoint-context-it";
    private static final TrustedKey PRODUCER = TrustedKey.active("auth-checkpoint-it-key-2026a", "auth-checkpoint-it",
            "checkpoint-it-hmac-key-not-a-secret".getBytes(StandardCharsets.UTF_8), Instant.parse("2026-01-01T00:00:00Z"));
    private static volatile String database;
    private static volatile String trustFile;
    private static final AtomicInteger BUCKETS = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_auth_checkpoint_it")
                    .withUsername("parkio")
                    .withPassword("parkio");

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
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        registry.add("management.tracing.enabled", () -> "false");
        String store = "parkio.privacy.account-erasure.durable-store.object-lock.";
        registry.add(store + "enabled", () -> "true");
        registry.add(store + "endpoint", ErasureCheckpointPostgresMinioIT::minioEndpoint);
        registry.add(store + "bucket", () -> CONTEXT_BUCKET);
        registry.add(store + "access-key", () -> ACCESS_KEY);
        registry.add(store + "secret-key", () -> SECRET_KEY);
        registry.add(store + "retention-mode", () -> "GOVERNANCE");
        registry.add(store + "retention", () -> "P1D");
        registry.add(store + "trust-file", ErasureCheckpointPostgresMinioIT::trustFile);
        registry.add(store + "producer-key-id", PRODUCER::keyId);
        registry.add("parkio.privacy.account-erasure.durable-store.checkpoint.enabled", () -> "true");
        try {
            ObjectLockTestBuckets.createLockedBucket(minioClient(), CONTEXT_BUCKET);
        } catch (Exception ex) {
            throw new IllegalStateException("could not create the object-lock test bucket", ex);
        }
    }

    @Autowired private ErasureCheckpointProducer producer;
    @Autowired private ObjectLockDurableErasureRecordStore contextStore;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void emptyLedger() {
        jdbc.execute("TRUNCATE erased_user_tombstones");
    }

    @Test
    void theEnabledProducerPublishesTheWholeTombstoneLedger() throws Exception {
        UUID first = tombstone(Instant.parse("2026-09-29T08:15:30.123456Z"));
        UUID second = tombstone(Instant.parse("2026-09-29T08:16:00Z"));
        UUID third = tombstone(Instant.parse("2026-09-29T08:17:45.120Z"));
        Instant before = Instant.now();

        DurableErasureCheckpoint checkpoint = producer.produce();

        assertThat(checkpoint.entryCount()).isEqualTo(3);
        assertThat(checkpoint.coveredThrough()).isAfter(before.minusSeconds(60));
        JsonNode body = JSON.readTree(new ObjectLockBucket(minioClient(), CONTEXT_BUCKET)
                .oldest(DurableErasureEvidence.checkpointKey(checkpoint.sequence())).orElseThrow().bytes());
        List<String> users = new ArrayList<>();
        List<String> erasedAt = new ArrayList<>();
        body.path("entries").forEach(entry -> {
            users.add(entry.path("authUserId").asText());
            erasedAt.add(entry.path("erasedAt").asText());
        });
        assertThat(users).isEqualTo(new ArrayList<>(new TreeSet<>(List.of(
                first.toString(), second.toString(), third.toString()))));
        assertThat(erasedAt).containsExactlyInAnyOrder(
                "2026-09-29T08:15:30.123456Z", "2026-09-29T08:16:00Z", "2026-09-29T08:17:45.120Z");
        assertThat(body.path("captureProtocol").asText()).isEqualTo("table-share-lock");
        RecoveryVerdict verdict = verifier().recover(contextStore.evidence(), null);
        assertThat(verdict.verdict()).isEqualTo(Verdict.ACCEPT_ISOLATED);
        assertThat(verdict.latestTrustedCheckpoint()).isEqualTo(checkpoint.sequence());
    }

    @Test
    void theStoreStartedWithATrustDocumentPinnedToThisDatabase() {
        String identity = PostgresDatabaseIdentity.of(jdbc);

        // system_identifier of this disposable cluster plus the database name.
        assertThat(identity).matches("postgresql:[0-9]+:parkio_auth_checkpoint_it");
        // The context started, so the startup check accepted the trust document pinned to it.
        assertThat(identity).isEqualTo(database());
        assertThat(contextStore).isNotNull();
    }

    @Test
    void theCaptureWaitsForAnInFlightInsertAndIncludesItAfterItCommits() throws Exception {
        ObjectLockDurableErasureRecordStore store = freshStore();
        UUID committed = tombstone(Instant.parse("2026-09-29T08:16:00Z"));
        UUID inFlight = UUID.randomUUID();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            insert(holder, inFlight, Instant.parse("2026-09-29T08:17:00Z"));

            CompletableFuture<DurableErasureCheckpoint> publication =
                    CompletableFuture.supplyAsync(() -> store.publishCheckpoint(capture(Duration.ofSeconds(30))));
            awaitShareLockRequests(true, Duration.ofSeconds(15));
            assertThat(publication).isNotDone();
            assertThat(storeKeys(store)).as("nothing is published while the capture waits").isEmpty();

            holder.commit();
            DurableErasureCheckpoint checkpoint = publication.get(30, TimeUnit.SECONDS);

            assertThat(checkpointUsers(store, checkpoint.sequence()))
                    .containsExactlyInAnyOrder(committed.toString(), inFlight.toString());
        }
    }

    @Test
    void aLockTimeoutRollsTheCaptureBackAndPublishesNothing() throws Exception {
        ObjectLockDurableErasureRecordStore store = freshStore();
        tombstone(Instant.parse("2026-09-29T08:16:00Z"));
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            insert(holder, UUID.randomUUID(), Instant.parse("2026-09-29T08:17:00Z"));

            assertThatThrownBy(() -> store.publishCheckpoint(capture(Duration.ofSeconds(1))))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("lock timeout");

            assertThat(storeKeys(store)).isEmpty();
            awaitShareLockRequests(false, Duration.ofSeconds(5));
            holder.rollback();
        }
    }

    @Test
    void theCheckpointIsPublishedOnlyAfterTheCaptureCommitted() {
        ObjectLockDurableErasureRecordStore store = freshStore();
        UUID user = tombstone(Instant.parse("2026-09-29T08:16:00Z"));
        ErasureLedgerCapture real = capture(Duration.ofSeconds(30));
        AtomicReference<List<String>> keysAfterCapture = new AtomicReference<>();
        AtomicReference<Integer> shareLocksAfterCapture = new AtomicReference<>();
        AtomicReference<Boolean> transactionAfterCapture = new AtomicReference<>();

        DurableErasureCheckpoint checkpoint = store.publishCheckpoint(() -> {
            CapturedErasureLedger ledger = real.capture();
            transactionAfterCapture.set(TransactionSynchronizationManager.isActualTransactionActive());
            shareLocksAfterCapture.set(shareLocks());
            keysAfterCapture.set(storeKeys(store));
            return ledger;
        });

        assertThat(transactionAfterCapture.get()).isFalse();
        assertThat(shareLocksAfterCapture.get()).as("SHARE lock released (capture committed)").isZero();
        assertThat(keysAfterCapture.get()).as("nothing published before the capture returned").isEmpty();
        assertThat(checkpointUsers(store, checkpoint.sequence())).containsExactly(user.toString());
    }

    @Test
    void theLatestCheckpointPlusTheRecordsAboveItCoverEveryTombstone() throws Exception {
        ObjectLockDurableErasureRecordStore store = freshStore();
        // Tombstones from before durable recording: no record, only checkpoints cover them.
        Set<UUID> erased = new HashSet<>(List.of(
                tombstone(Instant.parse("2026-09-29T08:00:00Z")), tombstone(Instant.parse("2026-09-29T08:01:00Z"))));
        erased.add(eraseAndRecord(store, Instant.parse("2026-09-29T08:02:00Z")));
        ErasureLedgerCapture real = capture(Duration.ofSeconds(30));
        AtomicReference<UUID> lateUser = new AtomicReference<>();
        AtomicReference<Throwable> lateFailure = new AtomicReference<>();
        AtomicReference<Thread.State> lateWriterWhileHeld = new AtomicReference<>();
        List<Thread> writers = new ArrayList<>();

        // An erasure commits after the capture and records itself before the checkpoint's
        // sequence is reserved: it must not get a sequence below the checkpoint.
        DurableErasureCheckpoint checkpoint = store.publishCheckpoint(() -> {
            CapturedErasureLedger ledger = real.capture();
            Thread writer = new Thread(() -> {
                try {
                    lateUser.set(eraseAndRecord(store, Instant.parse("2026-09-29T08:03:00Z")));
                } catch (Throwable failure) {
                    lateFailure.set(failure);
                }
            });
            writers.add(writer);
            writer.start();
            lateWriterWhileHeld.set(awaitBlockedOrDone(writer, Duration.ofSeconds(10)));
            return ledger;
        });
        writers.get(0).join(TimeUnit.SECONDS.toMillis(30));
        assertThat(lateFailure.get()).as("late erasure").isNull();
        erased.add(lateUser.get());
        erased.add(eraseAndRecord(store, Instant.parse("2026-09-29T08:04:00Z")));

        assertThat(lateWriterWhileHeld.get()).as("the late record waited for the checkpoint").isEqualTo(Thread.State.BLOCKED);
        RecoveryVerdict verdict = verifier().recover(store.evidence(), null);
        assertThat(verdict.verdict()).isEqualTo(Verdict.ACCEPT_ISOLATED);
        assertThat(verdict.gaps()).isEmpty();
        assertThat(verdict.latestTrustedCheckpoint()).isEqualTo(checkpoint.sequence());
        Set<String> union = new TreeSet<>(checkpointUsers(store, checkpoint.sequence()));
        for (VerifiedPending pending : verdict.pending()) {
            if (pending.sequence() > checkpoint.sequence()) {
                union.add(pending.authUserId());
            }
        }
        assertThat(union).isEqualTo(new TreeSet<>(erased.stream().map(UUID::toString).toList()));
        assertThat(union).isEqualTo(new TreeSet<>(jdbc.queryForList(
                "SELECT auth_user_id::text FROM erased_user_tombstones", String.class)));
    }

    /** One erasure as auth runs it: tombstone committed first, record persisted after commit. */
    private UUID eraseAndRecord(ObjectLockDurableErasureRecordStore store, Instant erasedAt) {
        UUID user = tombstone(erasedAt);
        store.putIfAbsent(DurableErasureRecord.of(UUID.randomUUID(), user, erasedAt));
        return user;
    }

    private UUID tombstone(Instant erasedAt) {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO erased_user_tombstones (auth_user_id, erased_at) VALUES (?, ?)",
                user, Timestamp.from(erasedAt));
        return user;
    }

    private static void insert(Connection connection, UUID user, Instant erasedAt) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO erased_user_tombstones (auth_user_id, erased_at) VALUES (?, ?)")) {
            insert.setObject(1, user);
            insert.setTimestamp(2, Timestamp.from(erasedAt));
            insert.executeUpdate();
        }
    }

    private ErasureLedgerCapture capture(Duration lockTimeout) {
        return new JdbcErasureLedgerCapture(jdbc, transactionManager, lockTimeout, Duration.ofSeconds(20));
    }

    /** SHARE locks on the tombstone table, held or requested. */
    private int shareLocks() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation
                WHERE c.relname = 'erased_user_tombstones' AND l.mode = 'ShareLock'
                """, Integer.class);
    }

    /** SHARE lock requests on the tombstone table that are still waiting. */
    private int waitingShareLocks() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation
                WHERE c.relname = 'erased_user_tombstones' AND l.mode = 'ShareLock' AND NOT l.granted
                """, Integer.class);
    }

    /** Waits until a capture is (or is no longer) waiting for its SHARE lock. */
    private void awaitShareLockRequests(boolean waiting, Duration patience) throws InterruptedException {
        long deadline = System.nanoTime() + patience.toNanos();
        while ((waitingShareLocks() > 0) != waiting) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("SHARE lock request " + (waiting ? "never appeared" : "still waiting"));
            }
            Thread.sleep(50);
        }
    }

    private static Thread.State awaitBlockedOrDone(Thread thread, Duration patience) {
        long deadline = System.nanoTime() + patience.toNanos();
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.BLOCKED || state == Thread.State.TERMINATED) {
                return state;
            }
            LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        }
        return thread.getState();
    }

    private ObjectLockDurableErasureRecordStore freshStore() {
        String bucket = "parkio-erasure-checkpoint-it-" + BUCKETS.incrementAndGet();
        try {
            ObjectLockTestBuckets.createLockedBucket(minioClient(), bucket);
        } catch (Exception ex) {
            throw new IllegalStateException("could not create the object-lock test bucket", ex);
        }
        return new ObjectLockDurableErasureRecordStore(new ObjectLockBucket(minioClient(), bucket), trust(), PRODUCER.keyId(),
                RetentionMode.GOVERNANCE, Duration.ofDays(1), Clock.systemUTC());
    }

    private static List<String> storeKeys(ObjectLockDurableErasureRecordStore store) {
        return store.evidence().list("");
    }

    private static List<String> checkpointUsers(ObjectLockDurableErasureRecordStore store, long sequence) {
        byte[] raw = store.evidence().get(DurableErasureEvidence.checkpointKey(sequence));
        verifier().verifyCheckpoint(raw);
        List<String> users = new ArrayList<>();
        try {
            JSON.readTree(raw).path("entries").forEach(entry -> users.add(entry.path("authUserId").asText()));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return users;
    }

    private static DurableErasureEvidenceVerifier verifier() {
        return new DurableErasureEvidenceVerifier(trust(), Instant.now());
    }

    /** The trust the service was started with: pinned to this container's database. */
    private static EvidenceTrust trust() {
        return new EvidenceTrust(database(), List.of(PRODUCER));
    }

    private static String database() {
        if (database == null) {
            database = TrustDocuments.identity(POSTGRES);
        }
        return database;
    }

    private static String trustFile() {
        if (trustFile == null) {
            trustFile = TrustDocuments.write(database(), PRODUCER).toString();
        }
        return trustFile;
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private static MinioClient minioClient() {
        return MinioClient.builder().endpoint(minioEndpoint()).credentials(ACCESS_KEY, SECRET_KEY).region("us-east-1").build();
    }
}
