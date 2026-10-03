package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.application.durable.CheckpointWatermark;
import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.RecoveryVerdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.Verdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedCheckpoint;
import com.parkio.auth.application.durable.DurableEvidenceException;
import com.parkio.auth.application.durable.ErasureLedgerEntry;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.application.port.CapturedErasureLedger;
import com.parkio.auth.application.port.DurableErasureCheckpoint;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.parkio.auth.application.port.ErasureLedgerCapture;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;
import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
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
 * Checkpoint publication in the object-lock store against a disposable MinIO bucket (the
 * captures are stubs; the PostgreSQL capture is ErasureCheckpointPostgresMinioIT): format v2
 * bytes under retention, sequences shared with records and monotonic, nothing published when the
 * capture fails, a reservation left by a failed publication filled by the next checkpoint, and a
 * consumer refusing an older checkpoint after a newer one. Synthetic keys and data only.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class ObjectLockCheckpointStoreIT {

    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String DATABASE = "auth-db:object-lock-checkpoint-it";
    private static final TrustedKey PRODUCER = TrustedKey.active("auth-object-lock-checkpoint-it-key-2026a",
            "auth-object-lock-checkpoint-it", "object-lock-checkpoint-it-key-not-a-secret".getBytes(StandardCharsets.UTF_8),
            Instant.parse("2026-01-01T00:00:00Z"));
    private static final EvidenceTrust TRUST = new EvidenceTrust(DATABASE, List.of(PRODUCER));
    private static final Duration RETENTION = Duration.ofDays(1);
    private static final AtomicInteger BUCKETS = new AtomicInteger();
    private static final Instant ERASED_AT = Instant.parse("2026-09-29T08:16:00Z");

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
        client = MinioClient.builder()
                .endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
                .credentials(ACCESS_KEY, SECRET_KEY)
                .region("us-east-1")
                .httpClient(new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(15)).build())
                .build();
        bucketName = "parkio-erasure-checkpoint-it-" + BUCKETS.incrementAndGet();
        ObjectLockTestBuckets.createLockedBucket(client, bucketName);
        bucket = new ObjectLockBucket(client, bucketName);
        store = store(client);
    }

    @Test
    void firstCheckpointPublishesFormatV1CheckpointMarkerAndFrontierUnderRetention() {
        List<ErasureLedgerEntry> entries = ledger(3);
        Instant before = clock.instant();

        DurableErasureCheckpoint checkpoint = store.publishCheckpoint(capture(entries));

        assertThat(checkpoint.sequence()).isEqualTo(1);
        assertThat(checkpoint.entryCount()).isEqualTo(3);
        assertThat(checkpoint.filledReservation()).isFalse();
        String key = DurableErasureEvidence.checkpointKey(1);
        ObjectLockBucket.StoredVersion published = bucket.oldest(key).orElseThrow();
        assertThat(published.bytes()).isEqualTo(DurableErasureEvidence.checkpoint(1, entries, DATABASE, PRODUCER));
        assertThat(bucket.oldest(DurableErasureEvidence.sequenceKey(1)).orElseThrow().bytes())
                .isEqualTo(DurableErasureEvidence.sequenceMarker(1, DurableErasureEvidence.CHECKPOINT_RESERVATION));
        assertThat(bucket.latest(DurableErasureEvidence.FRONTIER_KEY).orElseThrow().bytes())
                .isEqualTo(DurableErasureEvidence.frontier(1, 1, DATABASE, PRODUCER));
        assertThat(checkpoint.ledgerDigest()).isEqualTo(verifier().verifyCheckpoint(published.bytes()).ledgerDigest());
        Retention retention = bucket.retention(key, published.versionId());
        assertThat(retention.mode()).isEqualTo(RetentionMode.GOVERNANCE);
        assertThat(retention.retainUntilDate().toInstant()).isAfter(before.plus(RETENTION).minusSeconds(60));
        RecoveryVerdict verdict = recover();
        assertThat(verdict.verdict()).isEqualTo(Verdict.ACCEPT_ISOLATED);
        assertThat(verdict.latestTrustedCheckpoint()).isEqualTo(1L);
    }

    @Test
    void checkpointsAndRecordsShareOneMonotonicSequence() {
        store.putIfAbsent(record());
        DurableErasureCheckpoint first = store.publishCheckpoint(capture(ledger(1)));
        store.putIfAbsent(record());
        DurableErasureCheckpoint second = store.publishCheckpoint(capture(ledger(2)));

        assertThat(first.sequence()).isEqualTo(2);
        assertThat(second.sequence()).isEqualTo(4);
        RecoveryVerdict verdict = recover();
        assertThat(verdict.verdict()).isEqualTo(Verdict.ACCEPT_ISOLATED);
        assertThat(verdict.expectedThrough()).isEqualTo(4L);
        assertThat(verdict.gaps()).isEmpty();
        assertThat(verdict.latestTrustedCheckpoint()).isEqualTo(4L);
        assertThat(verdict.pending()).hasSize(2);
    }

    @Test
    void aFailedCaptureReservesAndPublishesNothing() {
        store.putIfAbsent(record());
        List<String> before = bucket.keys("");
        byte[] frontierBefore = bucket.latest(DurableErasureEvidence.FRONTIER_KEY).orElseThrow().bytes();
        int frontierVersionsBefore = bucket.versionCount(DurableErasureEvidence.FRONTIER_KEY);

        assertThatThrownBy(() -> store.publishCheckpoint(() -> {
            throw new IllegalStateException("capture rolled back: lock timeout");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("lock timeout");

        assertThat(bucket.keys("")).isEqualTo(before);
        assertThat(bucket.latest(DurableErasureEvidence.FRONTIER_KEY).orElseThrow().bytes()).isEqualTo(frontierBefore);
        assertThat(bucket.versionCount(DurableErasureEvidence.FRONTIER_KEY)).isEqualTo(frontierVersionsBefore);
    }

    @Test
    void checkpointPublicationRefusesToRunInsideADatabaseTransaction() {
        AtomicBoolean captured = new AtomicBoolean();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> store.publishCheckpoint(() -> {
                captured.set(true);
                return new CapturedErasureLedger(ledger(1), Instant.now());
            })).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(captured).isFalse();
        assertThat(bucket.keys("")).isEmpty();
    }

    @Test
    void aReservationLeftByAFailedPublicationIsFilledByTheNextCheckpoint() {
        CheckpointPutFailingMinioClient failing = new CheckpointPutFailingMinioClient(client);
        ObjectLockDurableErasureRecordStore unlucky = store(failing);
        unlucky.putIfAbsent(record());
        failing.failNextCheckpointPut();

        assertThatThrownBy(() -> unlucky.publishCheckpoint(capture(ledger(1))))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
        // Sequence 2 is reserved for the checkpoint; a later record takes 3 and raises the
        // frontier past it, so 2 is now a gap below the frontier.
        assertThat(bucket.oldest(DurableErasureEvidence.sequenceKey(2)).orElseThrow().bytes())
                .isEqualTo(DurableErasureEvidence.sequenceMarker(2, DurableErasureEvidence.CHECKPOINT_RESERVATION));
        store.putIfAbsent(record());
        RecoveryVerdict blocked = recover();
        assertThat(blocked.verdict()).isEqualTo(Verdict.BLOCKED);
        assertThat(blocked.gaps()).containsExactly(2L);

        DurableErasureCheckpoint filled = store.publishCheckpoint(capture(ledger(2)));

        assertThat(filled.sequence()).isEqualTo(2);
        assertThat(filled.filledReservation()).isTrue();
        assertThat(bucket.keys("sequences/")).hasSize(3);
        RecoveryVerdict verdict = recover();
        assertThat(verdict.verdict()).isEqualTo(Verdict.ACCEPT_ISOLATED);
        assertThat(verdict.expectedThrough()).isEqualTo(3L);
        assertThat(verdict.latestTrustedCheckpoint()).isEqualTo(2L);
        // The next checkpoint takes a new sequence again.
        assertThat(store.publishCheckpoint(capture(ledger(2))).sequence()).isEqualTo(4);
    }

    @Test
    void aConsumerRefusesAnOlderValidCheckpointAfterANewerOne() {
        store.publishCheckpoint(capture(ledger(1)));
        store.publishCheckpoint(capture(ledger(2)));
        DurableErasureEvidenceVerifier verifier = verifier();
        VerifiedCheckpoint older = verifier.verifyCheckpoint(bucket.oldest(DurableErasureEvidence.checkpointKey(1))
                .orElseThrow().bytes());
        VerifiedCheckpoint newer = verifier.verifyCheckpoint(bucket.oldest(DurableErasureEvidence.checkpointKey(2))
                .orElseThrow().bytes());

        CheckpointWatermark mark = CheckpointWatermark.first(older).accept(newer);

        assertThat(mark.sequence()).isEqualTo(2);
        assertThatThrownBy(() -> mark.accept(older))
                .isInstanceOf(DurableEvidenceException.class)
                .hasMessage("older valid checkpoint replayed; freshness failed");
    }

    private ObjectLockDurableErasureRecordStore store(MinioClient minio) {
        ObjectLockBucket target = new ObjectLockBucket(minio, bucketName);
        return new ObjectLockDurableErasureRecordStore(target, TRUST, PRODUCER.keyId(), RetentionMode.GOVERNANCE, RETENTION, clock);
    }

    private RecoveryVerdict recover() {
        return verifier().recover(store.evidence(), null);
    }

    private static DurableErasureEvidenceVerifier verifier() {
        return new DurableErasureEvidenceVerifier(TRUST, Instant.now());
    }

    private static ErasureLedgerCapture capture(List<ErasureLedgerEntry> entries) {
        return () -> new CapturedErasureLedger(entries, Instant.now());
    }

    private static List<ErasureLedgerEntry> ledger(int size) {
        return IntStream.range(0, size)
                .mapToObj(index -> new ErasureLedgerEntry(UUID.randomUUID(), ERASED_AT.plusSeconds(index)))
                .toList();
    }

    private static DurableErasureRecord record() {
        return DurableErasureRecord.of(UUID.randomUUID(), UUID.randomUUID(), ERASED_AT);
    }

    /** Fails the next checkpoint PUT, after its sequence marker was written. */
    static final class CheckpointPutFailingMinioClient extends MinioClient {

        private final AtomicBoolean armed = new AtomicBoolean();

        CheckpointPutFailingMinioClient(MinioClient client) {
            super(client);
        }

        void failNextCheckpointPut() {
            armed.set(true);
        }

        @Override
        public ObjectWriteResponse putObject(PutObjectArgs args)
                throws ErrorResponseException, InsufficientDataException, InternalException, InvalidKeyException,
                InvalidResponseException, IOException, NoSuchAlgorithmException, ServerException,
                XmlParserException {
            if (args.object().startsWith("checkpoints/") && armed.compareAndSet(true, false)) {
                throw new IOException("injected: the checkpoint write failed after its reservation");
            }
            return super.putObject(args);
        }
    }
}
