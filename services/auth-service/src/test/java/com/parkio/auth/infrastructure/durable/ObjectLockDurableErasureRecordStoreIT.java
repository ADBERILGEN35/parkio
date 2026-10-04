package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.RecoveryVerdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.Verdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedPending;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.application.port.DurableErasurePutResult;
import com.parkio.auth.application.port.DurableErasureReceipt;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The object-lock durable store against a disposable MinIO bucket with object lock and
 * versioning: format v2 objects, idempotent and conflicting retries, overwrite and delete
 * attempts, the returned publication receipt, COMPLIANCE against a governance bypass, a crash
 * between record and frontier, recovery from the bucket alone, store timeouts and the
 * no-transaction rule. Synthetic keys and data only.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ObjectLockDurableErasureRecordStoreIT {

    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String DATABASE = "auth-db:object-lock-it";
    private static final TrustedKey PRODUCER = TrustedKey.active("auth-object-lock-it-key-2026a", "auth-object-lock-it",
            "object-lock-it-key-not-a-secret".getBytes(StandardCharsets.UTF_8), Instant.parse("2026-01-01T00:00:00Z"));
    private static final EvidenceTrust TRUST = new EvidenceTrust(DATABASE, List.of(PRODUCER));
    private static final Duration RETENTION = Duration.ofDays(1);
    private static final AtomicInteger BUCKETS = new AtomicInteger();

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

    private final Clock clock = Clock.systemUTC();
    private MinioClient client;
    private String bucketName;
    private ObjectLockBucket bucket;
    private ObjectLockDurableErasureRecordStore store;

    @BeforeEach
    void freshLockedBucket() throws Exception {
        client = client(Duration.ofSeconds(15));
        bucketName = "parkio-erasure-evidence-it-" + BUCKETS.incrementAndGet();
        ObjectLockTestBuckets.createLockedBucket(client, bucketName);
        bucket = new ObjectLockBucket(client, bucketName);
        bucket.requireObjectLock();
        store = new ObjectLockDurableErasureRecordStore(bucket, TRUST, PRODUCER.keyId(), RetentionMode.GOVERNANCE, RETENTION, clock);
    }

    @Test
    void firstPutPublishesFormatV1RecordMarkerAndFrontier() {
        DurableErasureRecord record = record("2026-09-29T08:15:30.123456Z");

        DurableErasurePutResult result = store.putIfAbsent(record);

        assertThat(result.created()).isTrue();
        assertThat(result.conflict()).isFalse();
        assertThat(store.findByRequestId(record.erasureRequestId())).contains(record);
        assertThat(bucket.oldest(DurableErasureEvidence.recordKey(record.erasureRequestId())).orElseThrow().bytes())
                .isEqualTo(DurableErasureEvidence.pendingRecord(record, 1, DATABASE, PRODUCER));
        assertThat(bucket.oldest(DurableErasureEvidence.sequenceKey(1)).orElseThrow().bytes())
                .isEqualTo(DurableErasureEvidence.sequenceMarker(1, record.erasureRequestId()));
        assertThat(ObjectLockTestBuckets.currentFrontier(bucket, verifier()))
                .isEqualTo(DurableErasureEvidence.frontier(1, 1, DATABASE, PRODUCER));
    }

    @Test
    void identicalRetryIsIdempotent() {
        DurableErasureRecord record = record("2026-09-29T08:16:00Z");
        store.putIfAbsent(record);

        DurableErasurePutResult retry = store.putIfAbsent(record);

        assertThat(retry.created()).isFalse();
        assertThat(retry.conflict()).isFalse();
        assertThat(retry.record()).isEqualTo(record);
        assertThat(bucket.versionCount(DurableErasureEvidence.recordKey(record.erasureRequestId()))).isOne();
        assertThat(bucket.keys("sequences/")).hasSize(1);
        assertThat(recover().expectedThrough()).isEqualTo(1L);
    }

    @Test
    void conflictingRetryIsRejectedAndTheRecordStays() {
        DurableErasureRecord record = record("2026-09-29T08:16:00Z");
        store.putIfAbsent(record);
        DurableErasureRecord different = DurableErasureRecord.of(
                record.erasureRequestId(), record.authUserId(), Instant.parse("2026-09-29T08:17:00Z"));

        DurableErasurePutResult conflict = store.putIfAbsent(different);

        assertThat(conflict.conflict()).isTrue();
        assertThat(conflict.record()).isEqualTo(record);
        assertThat(store.findByRequestId(record.erasureRequestId())).contains(record);
        assertThat(bucket.versionCount(DurableErasureEvidence.recordKey(record.erasureRequestId()))).isOne();
    }

    @Test
    void overwriteAndDeleteAttemptsDoNotChangeThePublishedRecord() throws Exception {
        DurableErasureRecord record = record("2026-09-29T08:15:30.123456Z");
        store.putIfAbsent(record);
        String key = DurableErasureEvidence.recordKey(record.erasureRequestId());
        ObjectLockBucket.StoredVersion published = bucket.oldest(key).orElseThrow();

        // A later write only adds a version; the first version stays canonical.
        byte[] forged = DurableErasureEvidence.pendingRecord(record("2026-09-29T09:00:00Z", record.erasureRequestId()), 1,
                DATABASE, PRODUCER);
        client.putObject(PutObjectArgs.builder().bucket(bucketName).object(key)
                .stream(new ByteArrayInputStream(forged), forged.length, -1).build());
        // Deleting the locked version is refused by the bucket.
        assertThatThrownBy(() -> client.removeObject(RemoveObjectArgs.builder()
                .bucket(bucketName).object(key).versionId(published.versionId()).build()))
                .isInstanceOf(ErrorResponseException.class);
        // A plain delete only adds a delete marker.
        client.removeObject(RemoveObjectArgs.builder().bucket(bucketName).object(key).build());

        assertThat(store.findByRequestId(record.erasureRequestId())).contains(record);
        assertThat(store.putIfAbsent(record).conflict()).isFalse();
        RecoveryVerdict verdict = recover();
        assertThat(verdict.verdict()).isEqualTo(Verdict.ACCEPT_ISOLATED);
        assertThat(verdict.pending()).extracting(VerifiedPending::erasedAt).containsExactly("2026-09-29T08:15:30.123456Z");
    }

    @Test
    void putReturnsTheReceiptOfTheLockedCanonicalVersion() throws Exception {
        DurableErasureRecord record = record("2026-09-29T08:16:00Z");
        Instant before = clock.instant();
        DurableErasureReceipt receipt = store.putIfAbsent(record).receipt();
        String key = DurableErasureEvidence.recordKey(record.erasureRequestId());

        // The bucket's own view of the canonical version, read independently of the store.
        ObjectLockBucket.StoredVersion canonical = bucket.oldest(key).orElseThrow();
        Retention retention = bucket.retention(key, canonical.versionId());

        assertThat(receipt.versionId()).isEqualTo(canonical.versionId());
        assertThat(receipt.sha256())
                .isEqualTo(sha256(canonical.bytes()))
                .isEqualTo(sha256(DurableErasureEvidence.pendingRecord(record, 1, DATABASE, PRODUCER)));
        assertThat(receipt.retentionMode()).isEqualTo("GOVERNANCE").isEqualTo(retention.mode().name());
        assertThat(receipt.retainUntil())
                .isEqualTo(retention.retainUntilDate().toInstant())
                .isBetween(before.plus(RETENTION).minus(1, ChronoUnit.MINUTES), clock.instant().plus(RETENTION).plus(1, ChronoUnit.MINUTES));

        // An identical retry and a conflicting retry name the same canonical version.
        DurableErasurePutResult retry = store.putIfAbsent(record);
        DurableErasurePutResult conflict = store.putIfAbsent(
                DurableErasureRecord.of(record.erasureRequestId(), record.authUserId(), Instant.parse("2026-09-29T08:17:00Z")));
        assertThat(retry.created()).isFalse();
        assertThat(retry.receipt()).isEqualTo(receipt);
        assertThat(conflict.conflict()).isTrue();
        assertThat(conflict.receipt()).isEqualTo(receipt);
    }

    @Test
    void complianceRefusesTheGovernanceBypassThatDeletesAGovernanceLockedRecord() throws Exception {
        DurableErasureRecord governed = record("2026-09-29T08:16:00Z");
        DurableErasureReceipt governedReceipt = store.putIfAbsent(governed).receipt();
        ObjectLockDurableErasureRecordStore compliance = new ObjectLockDurableErasureRecordStore(
                bucket, TRUST, PRODUCER.keyId(), RetentionMode.COMPLIANCE, RETENTION, clock);
        DurableErasureRecord locked = record("2026-09-29T08:17:00Z");
        DurableErasureReceipt lockedReceipt = compliance.putIfAbsent(locked).receipt();
        String governedKey = DurableErasureEvidence.recordKey(governed.erasureRequestId());
        String lockedKey = DurableErasureEvidence.recordKey(locked.erasureRequestId());

        assertThat(governedReceipt.retentionMode()).isEqualTo("GOVERNANCE");
        assertThat(lockedReceipt.retentionMode()).isEqualTo("COMPLIANCE");
        assertThat(bucket.retention(lockedKey, lockedReceipt.versionId()).mode()).isEqualTo(RetentionMode.COMPLIANCE);

        // A principal allowed to bypass governance retention (here the root user) deletes the
        // GOVERNANCE-locked canonical version...
        bypassDelete(governedKey, governedReceipt.versionId());
        assertThat(bucket.versionCount(governedKey)).isZero();
        assertThat(compliance.findByRequestId(governed.erasureRequestId())).isEmpty();
        // ...and the same request is refused for the COMPLIANCE-locked one.
        assertThatThrownBy(() -> bypassDelete(lockedKey, lockedReceipt.versionId()))
                .isInstanceOf(ErrorResponseException.class);
        assertThat(bucket.oldest(lockedKey).orElseThrow().versionId()).isEqualTo(lockedReceipt.versionId());
        assertThat(compliance.findByRequestId(locked.erasureRequestId())).contains(locked);
    }

    @Test
    void aRecordPublishedWithoutItsFrontierIsNotDurableUntilTheNextPut() {
        DurableErasureRecord first = record("2026-09-29T08:16:00Z");
        store.putIfAbsent(first);
        // Crash after reserving sequence 2 and publishing the record, before the frontier moved.
        DurableErasureRecord second = record("2026-09-29T08:17:45.120Z");
        Retention lock = new Retention(RetentionMode.GOVERNANCE, clock.instant().plus(RETENTION).atZone(ZoneOffset.UTC));
        bucket.put(DurableErasureEvidence.sequenceKey(2), DurableErasureEvidence.sequenceMarker(2, second.erasureRequestId()),
                lock.mode(), lock.retainUntilDate());
        bucket.put(DurableErasureEvidence.recordKey(second.erasureRequestId()),
                DurableErasureEvidence.pendingRecord(second, 2, DATABASE, PRODUCER), lock.mode(), lock.retainUntilDate());

        assertThat(store.findByRequestId(second.erasureRequestId())).isEmpty();
        assertThat(recover().expectedThrough()).isEqualTo(1L);

        DurableErasurePutResult retry = store.putIfAbsent(second);

        assertThat(retry.created()).isFalse();
        assertThat(retry.conflict()).isFalse();
        assertThat(store.findByRequestId(second.erasureRequestId())).contains(second);
        assertThat(recover().expectedThrough()).isEqualTo(2L);
        assertThat(bucket.keys("sequences/")).hasSize(2);
    }

    @Test
    void recoveryAfterPrimaryHostLossNeedsOnlyTheBucket() {
        for (String erasedAt : new String[] {"2026-09-29T08:15:30.123456Z", "2026-09-29T08:16:00Z", "2026-09-29T08:17:45.120Z"}) {
            store.putIfAbsent(record(erasedAt));
        }

        // A fresh client and verifier: no auth database, no in-memory state from the writer.
        DurableErasureEvidenceVerifier verifier = new DurableErasureEvidenceVerifier(TRUST, clock.instant());
        RecoveryVerdict verdict = verifier.recover(
                ObjectLockEvidenceObjects.connect(client(Duration.ofSeconds(15)), bucketName), 3L);

        assertThat(verdict.verdict()).isEqualTo(Verdict.ACCEPT_ISOLATED);
        assertThat(verdict.expectedThrough()).isEqualTo(3L);
        assertThat(verdict.gaps()).isEmpty();
        assertThat(verdict.pending()).hasSize(3);
    }

    @Test
    void anUnreachableStoreFailsWithinTheCallTimeout() throws Exception {
        ObjectLockDurableErasureRecordStore impatient = new ObjectLockDurableErasureRecordStore(
                new ObjectLockBucket(client(Duration.ofSeconds(2)), bucketName), TRUST, PRODUCER.keyId(),
                RetentionMode.GOVERNANCE, RETENTION, clock);
        MINIO.getDockerClient().pauseContainerCmd(MINIO.getContainerId()).exec();
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> impatient.putIfAbsent(record("2026-09-29T08:16:00Z")))
                    .isInstanceOf(AuthException.class)
                    .extracting(ex -> ((AuthException) ex).errorCode())
                    .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        } finally {
            MINIO.getDockerClient().unpauseContainerCmd(MINIO.getContainerId()).exec();
            ObjectLockTestBuckets.awaitReady("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        }
    }

    @Test
    void storeIoRefusesToRunInsideADatabaseTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> store.putIfAbsent(record("2026-09-29T08:16:00Z")))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.findByRequestId(UUID.randomUUID()))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(bucket.keys("")).isEmpty();
    }

    private RecoveryVerdict recover() {
        return verifier().recover(store.evidence(), null);
    }

    private DurableErasureEvidenceVerifier verifier() {
        return new DurableErasureEvidenceVerifier(TRUST, clock.instant());
    }

    private void bypassDelete(String key, String versionId) throws Exception {
        client.removeObject(RemoveObjectArgs.builder()
                .bucket(bucketName).object(key).versionId(versionId).bypassGovernanceMode(true).build());
    }

    private static DurableErasureRecord record(String erasedAt) {
        return record(erasedAt, UUID.randomUUID());
    }

    private static DurableErasureRecord record(String erasedAt, UUID requestId) {
        return DurableErasureRecord.of(requestId, UUID.randomUUID(), Instant.parse(erasedAt));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static MinioClient client(Duration callTimeout) {
        return MinioClient.builder()
                .endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
                .credentials(ACCESS_KEY, SECRET_KEY)
                .region("us-east-1")
                .httpClient(new OkHttpClient.Builder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .callTimeout(callTimeout)
                        .build())
                .build();
    }
}
