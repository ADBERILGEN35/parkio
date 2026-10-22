package com.parkio.auth.infrastructure.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.cloud.storage.StorageException;
import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.ErasureLedgerEntry;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.application.port.CapturedErasureLedger;
import com.parkio.auth.application.port.DurableErasureCheckpoint;
import com.parkio.auth.application.port.DurableErasurePutResult;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Local behavioural evidence for the GCS Object Retention Lock adapter through a controlled
 * transport stub. <strong>Not</strong> real Google Cloud acceptance (#483).
 */
class GcsObjectRetentionBucketTest {

    private static final String DATABASE = "postgresql:7000000000000000007:parkio_auth";
    private static final byte[] SECRET = "gcs-adapter-test-hmac-key-not-a-secret!!".getBytes(StandardCharsets.UTF_8);
    private static final TrustedKey PRODUCER = TrustedKey.active("gcs-adapter-key-2026a", "gcs-adapter-test", SECRET,
            Instant.parse("2026-01-01T00:00:00Z"));
    private static final EvidenceTrust TRUST = new EvidenceTrust(DATABASE, List.of(PRODUCER));
    private static final Duration RETENTION = Duration.ofHours(2);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);

    private InMemoryGcsObjectRetentionTransport transport;
    private GcsObjectRetentionBucket bucket;
    private ObjectLockDurableErasureRecordStore store;

    @BeforeEach
    void setUp() {
        transport = new InMemoryGcsObjectRetentionTransport("parkio-erasure-evidence-test");
        transport.setForcedPageSizeOverride(2);
        transport.setListNewestFirst(true);
        bucket = new GcsObjectRetentionBucket(transport);
        store = new ObjectLockDurableErasureRecordStore(bucket, TRUST, PRODUCER.keyId(),
                EvidenceRetentionMode.GOVERNANCE, RETENTION, CLOCK);
    }

    @Test
    void requireRetentionFeatureRefusesWhenObjectRetentionLockIsDisabled() {
        transport.setObjectRetentionEnabled(false);
        assertThatThrownBy(bucket::requireRetentionFeature)
                .isInstanceOf(AuthException.class)
                .hasMessage("durable store object retention configuration failed: bucket parkio-erasure-evidence-test"
                        + " does not have Object Retention Lock enabled")
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
    }

    @Test
    void requireRetentionFeatureRefusesWhenObjectVersioningIsDisabled() {
        transport.setVersioningEnabled(false);
        assertThatThrownBy(bucket::requireRetentionFeature)
                .isInstanceOf(AuthException.class)
                .hasMessage("durable store object retention configuration failed: bucket parkio-erasure-evidence-test"
                        + " does not have Object Versioning enabled")
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
    }

    @Test
    void oldestIsLowestGenerationEvenWhenListingReturnsNewestFirstAcrossPages() {
        var retainUntil = CLOCK.instant().plus(RETENTION).atZone(ZoneOffset.UTC);
        String key = "records/" + UUID.randomUUID() + ".json";
        String first = bucket.put(key, "one".getBytes(StandardCharsets.UTF_8), EvidenceRetentionMode.GOVERNANCE, retainUntil);
        String second = bucket.put(key, "two".getBytes(StandardCharsets.UTF_8), EvidenceRetentionMode.GOVERNANCE, retainUntil);
        String third = bucket.put(key, "three".getBytes(StandardCharsets.UTF_8), EvidenceRetentionMode.GOVERNANCE, retainUntil);

        assertThat(Long.parseLong(first)).isLessThan(Long.parseLong(second)).isLessThan(Long.parseLong(third));
        assertThat(bucket.oldest(key).orElseThrow().versionId()).isEqualTo(first);
        assertThat(new String(bucket.oldest(key).orElseThrow().bytes(), StandardCharsets.UTF_8)).isEqualTo("one");
        // Listing encounter order is newest-first and paginated (page size 2); canonical stays oldest.
        assertThat(bucket.allVersions(key)).hasSize(3);
        assertThat(bucket.allVersions(key).get(0).versionId()).isEqualTo(third);
    }

    @Test
    void putWithRetentionIsInspectableAndComplianceCannotBeShortenedInStub() {
        var retainUntil = CLOCK.instant().plus(RETENTION).atZone(ZoneOffset.UTC);
        String key = "markers/locked.json";
        String generation = bucket.put(key, "{}".getBytes(StandardCharsets.UTF_8), EvidenceRetentionMode.COMPLIANCE, retainUntil);
        DurableEvidenceBucket.RetentionLock lock = bucket.retention(key, generation);
        assertThat(lock.mode()).isEqualTo(EvidenceRetentionMode.COMPLIANCE);
        assertThat(lock.retainUntil()).isEqualTo(retainUntil.toInstant());
        assertThat(transport.tryShortenCompliance(key, Long.parseLong(generation), retainUntil.toInstant().minusSeconds(60)))
                .isFalse();
    }

    @Test
    void storeProtocolUsesGcsBucketForFrontierCompletenessUnderPagination() {
        DurableErasureRecord first = DurableErasureRecord.of(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-10-21T11:00:00Z"));
        DurableErasureRecord second = DurableErasureRecord.of(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-10-21T11:01:00Z"));
        DurableErasurePutResult created = store.putIfAbsent(first);
        DurableErasurePutResult again = store.putIfAbsent(second);

        assertThat(created.created()).isTrue();
        assertThat(again.created()).isTrue();
        assertThat(store.findByRequestId(first.erasureRequestId())).contains(first);
        assertThat(store.findByRequestId(second.erasureRequestId())).contains(second);
        assertThat(bucket.keys("sequences/")).hasSize(2);
        assertThat(bucket.versionCount(DurableErasureEvidence.FRONTIER_KEY)).isGreaterThanOrEqualTo(2);
        assertThat(created.receipt().retentionMode()).isEqualTo("GOVERNANCE");
    }

    @Test
    void concurrentOverwriteKeepsOldestCanonicalForIdenticalRetry() {
        DurableErasureRecord record = DurableErasureRecord.of(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-10-21T11:02:00Z"));
        DurableErasurePutResult first = store.putIfAbsent(record);
        DurableErasurePutResult retry = store.putIfAbsent(record);
        assertThat(retry.created()).isFalse();
        assertThat(retry.receipt().versionId()).isEqualTo(first.receipt().versionId());
        String key = DurableErasureEvidence.recordKey(record.erasureRequestId());
        assertThat(bucket.oldest(key).orElseThrow().versionId()).isEqualTo(first.receipt().versionId());
    }

    @Test
    void putIfAbsentCompletesWhenTheProviderThrottlesTheSecondFrontierWrite() {
        transport.enforceWriteRatePerObject(Clock.systemUTC());
        DurableErasureRecord record = DurableErasureRecord.of(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-10-21T11:03:00Z"));

        DurableErasurePutResult result = store.putIfAbsent(record);

        assertThat(result.created()).isTrue();
        assertThat(store.findByRequestId(record.erasureRequestId())).contains(record);
    }

    @Test
    void publishCheckpointCompletesWhenTheProviderThrottlesTheSecondFrontierWrite() {
        transport.enforceWriteRatePerObject(Clock.systemUTC());
        Instant capturedAt = Instant.parse("2026-10-21T11:04:00Z");

        DurableErasureCheckpoint checkpoint = store.publishCheckpoint(() -> new CapturedErasureLedger(
                List.of(new ErasureLedgerEntry(UUID.randomUUID(), capturedAt.minusSeconds(60))), capturedAt));

        assertThat(checkpoint.entryCount()).isEqualTo(1);
        assertThat(bucket.keys("checkpoints/")).hasSize(1);
        assertThat(bucket.keys("sequences/")).hasSize(1);
    }

    @Test
    void aWriteRefusedWithHttp429IsResentAfterEachPauseAndThenFails() {
        List<Duration> pauses = new ArrayList<>();
        RefusingTransport refusing = new RefusingTransport(transport, Integer.MAX_VALUE, 429);
        GcsObjectRetentionBucket throttled = new GcsObjectRetentionBucket(refusing, pauses::add);

        assertThatThrownBy(() -> throttled.put("records/x.json", new byte[] {1}, EvidenceRetentionMode.GOVERNANCE,
                CLOCK.instant().plus(RETENTION).atZone(ZoneOffset.UTC)))
                .isInstanceOf(AuthException.class)
                .hasMessage("durable store put records/x.json failed: StorageException");
        assertThat(refusing.attempts).hasValue(GcsObjectRetentionBucket.RATE_LIMIT_PAUSES.size() + 1);
        assertThat(pauses).isEqualTo(GcsObjectRetentionBucket.RATE_LIMIT_PAUSES);
        assertThat(bucket.versionCount("records/x.json")).isZero();
    }

    @Test
    void aResentWriteThatIsAcceptedIsStoredOnce() {
        List<Duration> pauses = new ArrayList<>();
        RefusingTransport refusing = new RefusingTransport(transport, 2, 429);
        GcsObjectRetentionBucket throttled = new GcsObjectRetentionBucket(refusing, pauses::add);

        String generation = throttled.put("records/y.json", new byte[] {1}, EvidenceRetentionMode.GOVERNANCE,
                CLOCK.instant().plus(RETENTION).atZone(ZoneOffset.UTC));

        assertThat(refusing.attempts).hasValue(3);
        assertThat(pauses).containsExactly(Duration.ofMillis(1_100), Duration.ofMillis(2_200));
        assertThat(bucket.versionCount("records/y.json")).isEqualTo(1);
        assertThat(bucket.oldest("records/y.json").orElseThrow().versionId()).isEqualTo(generation);
    }

    @Test
    void otherWriteFailuresAreNotResent() {
        List<Duration> pauses = new ArrayList<>();
        RefusingTransport refusing = new RefusingTransport(transport, 1, 503);
        GcsObjectRetentionBucket failing = new GcsObjectRetentionBucket(refusing, pauses::add);

        assertThatThrownBy(() -> failing.put("records/z.json", new byte[] {1}, EvidenceRetentionMode.GOVERNANCE,
                CLOCK.instant().plus(RETENTION).atZone(ZoneOffset.UTC)))
                .isInstanceOf(AuthException.class);
        assertThat(refusing.attempts).hasValue(1);
        assertThat(pauses).isEmpty();
    }

    @Test
    void anInterruptedPauseFailsThePutAndKeepsTheInterrupt() {
        RefusingTransport refusing = new RefusingTransport(transport, 1, 429);
        GcsObjectRetentionBucket interrupted = new GcsObjectRetentionBucket(refusing, duration -> {
            throw new InterruptedException("stop");
        });
        try {
            assertThatThrownBy(() -> interrupted.put("records/i.json", new byte[] {1},
                    EvidenceRetentionMode.GOVERNANCE, CLOCK.instant().plus(RETENTION).atZone(ZoneOffset.UTC)))
                    .isInstanceOf(AuthException.class)
                    .hasMessage("durable store put records/i.json failed: InterruptedException");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(refusing.attempts).hasValue(1);
    }

    @Test
    void missingPermissionsAndUnavailableStorageMapToDurableRecordingUnavailable() {
        transport.setMissingWritePermission(true);
        assertThatThrownBy(() -> bucket.put("x", new byte[] {1}, EvidenceRetentionMode.GOVERNANCE,
                CLOCK.instant().plus(RETENTION).atZone(ZoneOffset.UTC)))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);

        transport.setMissingWritePermission(false);
        transport.setMissingListPermission(true);
        assertThatThrownBy(() -> bucket.keys("sequences/"))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);

        transport.setMissingListPermission(false);
        transport.setUnavailable(true);
        assertThatThrownBy(bucket::requireRetentionFeature)
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
    }

    /** Refuses the first {@code refusals} writes with the given HTTP status; everything else is delegated. */
    private static final class RefusingTransport implements GcsObjectRetentionTransport {

        final AtomicInteger attempts = new AtomicInteger();
        private final GcsObjectRetentionTransport delegate;
        private final int refusals;
        private final int code;

        RefusingTransport(GcsObjectRetentionTransport delegate, int refusals, int code) {
            this.delegate = delegate;
            this.refusals = refusals;
            this.code = code;
        }

        @Override
        public String bucket() {
            return delegate.bucket();
        }

        @Override
        public void requireObjectRetentionEnabled() {
            delegate.requireObjectRetentionEnabled();
        }

        @Override
        public long createGeneration(String objectName, byte[] bytes, EvidenceRetentionMode mode,
                                     Instant retainUntil) {
            if (attempts.incrementAndGet() <= refusals) {
                throw new StorageException(code, "refused by the test transport");
            }
            return delegate.createGeneration(objectName, bytes, mode, retainUntil);
        }

        @Override
        public RetentionState retention(String objectName, long generation) {
            return delegate.retention(objectName, generation);
        }

        @Override
        public ListPage listGenerations(String prefix, Optional<String> pageToken, int pageSize) {
            return delegate.listGenerations(prefix, pageToken, pageSize);
        }

        @Override
        public byte[] readGeneration(String objectName, long generation) {
            return delegate.readGeneration(objectName, generation);
        }
    }
}
