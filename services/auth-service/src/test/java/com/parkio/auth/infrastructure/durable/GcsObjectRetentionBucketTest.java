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
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

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

    @Test
    void aRepeatedPageTokenFailsTheListingClosed() {
        // The provider answers page-token-b with page-token-a again, so following tokens would loop
        // forever. The stub ends after 20 pages so that an unbounded listing finishes instead of hanging.
        PagingTransport paging = new PagingTransport(request -> request == 20
                ? lastPage()
                : pageWithToken(request % 2 == 0 ? "page-token-a" : "page-token-b"));
        GcsObjectRetentionBucket cycling = bounded(paging,
                new GcsObjectRetentionBucket.ListBounds(1_000, Duration.ofMinutes(5)), () -> 0L);

        assertThatThrownBy(() -> cycling.keys("sequences/"))
                .isInstanceOf(AuthException.class)
                .hasMessage("durable store list sequences/ failed: page token repeated")
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
        assertThat(paging.requestedTokens).containsExactly(Optional.empty(), Optional.of("page-token-a"),
                Optional.of("page-token-b"));
    }

    @Test
    void aListingLongerThanTheMaximumNumberOfPagesFailsClosed() {
        // Four pages, each with a fresh token, against a bound of three.
        String key = DurableErasureEvidence.FRONTIER_KEY;
        PagingTransport paging = new PagingTransport(request -> new GcsObjectRetentionTransport.ListPage(
                List.of(new GcsObjectRetentionTransport.ListedGeneration(key, request + 1, false)),
                request < 3 ? Optional.of("page-token-" + request) : Optional.empty()));
        GcsObjectRetentionBucket bounded = bounded(paging,
                new GcsObjectRetentionBucket.ListBounds(3, Duration.ofMinutes(5)), () -> 0L);

        assertThatThrownBy(() -> bounded.versionCount(key))
                .isInstanceOf(AuthException.class)
                .hasMessage("durable store list " + key + " failed: more than 3 pages")
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
        assertThat(paging.requestedTokens).hasSize(3);
    }

    @Test
    void aListingOfExactlyTheMaximumNumberOfPagesIsComplete() {
        String key = DurableErasureEvidence.FRONTIER_KEY;
        PagingTransport paging = new PagingTransport(request -> new GcsObjectRetentionTransport.ListPage(
                List.of(new GcsObjectRetentionTransport.ListedGeneration(key, request + 1, false)),
                request < 2 ? Optional.of("page-token-" + request) : Optional.empty()));
        GcsObjectRetentionBucket bounded = bounded(paging,
                new GcsObjectRetentionBucket.ListBounds(3, Duration.ofMinutes(5)), () -> 0L);

        assertThat(bounded.versionCount(key)).isEqualTo(3);
        assertThat(paging.requestedTokens).hasSize(3);
    }

    @Test
    void aListingStillPagingAtItsDeadlineFailsClosed() {
        // Each page takes 11 s on a fake monotonic clock against a 30 s deadline, so a fourth page is
        // never requested. The stub ends after 50 pages so that an unbounded listing finishes.
        AtomicLong nanos = new AtomicLong();
        PagingTransport paging = new PagingTransport(request -> {
            nanos.addAndGet(Duration.ofSeconds(11).toNanos());
            return request < 50 ? pageWithToken("page-token-" + request) : lastPage();
        });
        GcsObjectRetentionBucket slow = bounded(paging,
                new GcsObjectRetentionBucket.ListBounds(1_000, Duration.ofSeconds(30)), nanos::get);

        assertThatThrownBy(() -> slow.oldest("records/slow.json"))
                .isInstanceOf(AuthException.class)
                .hasMessage("durable store list records/slow.json failed: deadline PT30S passed")
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
        assertThat(paging.requestedTokens).hasSize(3);
    }

    /**
     * #616 F1: the deadline also bounds a page in flight. The provider never answers the first page,
     * so the listing fails closed when the deadline passes and abandons the request, instead of
     * waiting under the store's lock for the client's own timeouts (20 s a request, 50 s with retries).
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aPageThatNeverArrivesFailsTheListingAtItsDeadline() throws InterruptedException {
        CountDownLatch interrupted = new CountDownLatch(1);
        PagingTransport stalled = new PagingTransport(request -> stall(interrupted));
        GcsObjectRetentionBucket bounded = bounded(stalled,
                new GcsObjectRetentionBucket.ListBounds(1_000, Duration.ofMillis(300)), System::nanoTime);

        long started = System.nanoTime();
        assertThatThrownBy(() -> bounded.oldest("records/stalled.json"))
                .isInstanceOf(AuthException.class)
                .hasMessage("durable store list records/stalled.json failed: deadline PT0.3S passed while page 1 was"
                        + " in flight")
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(300)).isLessThan(Duration.ofSeconds(5));
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("the abandoned page request is interrupted").isTrue();
        assertThat(stalled.requestedTokens).containsExactly(Optional.empty());
    }

    /**
     * #616 F1: a later page gets only what is left of the deadline, not a deadline of its own. The
     * first page uses 59 of the 60 s on a fake clock; the second never answers, so the listing ends
     * about 1 s later instead of 60 s later.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStalledLaterPageGetsOnlyWhatIsLeftOfTheDeadline() {
        AtomicLong skipped = new AtomicLong();
        CountDownLatch interrupted = new CountDownLatch(1);
        PagingTransport paging = new PagingTransport(request -> {
            if (request > 0) {
                return stall(interrupted);
            }
            skipped.addAndGet(Duration.ofSeconds(59).toNanos());
            return pageWithToken("page-token-0");
        });
        GcsObjectRetentionBucket bounded = bounded(paging,
                new GcsObjectRetentionBucket.ListBounds(1_000, Duration.ofMinutes(1)),
                () -> System.nanoTime() + skipped.get());

        long started = System.nanoTime();
        assertThatThrownBy(() -> bounded.keys("sequences/"))
                .isInstanceOf(AuthException.class)
                .hasMessage("durable store list sequences/ failed: deadline PT1M passed while page 2 was in flight");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(paging.requestedTokens).containsExactly(Optional.empty(), Optional.of("page-token-0"));
    }

    /** #616 F4: the bounds have upper limits, so a very large value cannot switch them off. */
    @Test
    void listBoundsHaveUpperLimits() {
        assertThat(new GcsObjectRetentionBucket.ListBounds(10_000, Duration.ofMinutes(5)).maxPages()).isEqualTo(10_000);
        assertThatThrownBy(() -> new GcsObjectRetentionBucket.ListBounds(10_001, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxPages must be at most 10000");
        assertThatThrownBy(() -> new GcsObjectRetentionBucket.ListBounds(1_000, Duration.ofMinutes(5).plusMillis(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("deadline must be at most PT5M");
    }

    /**
     * #616 F2: a listing that completes past half of either bound logs a warning, while the next
     * listings of a history that only grows still complete; one within both halves logs nothing.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aListingPastHalfOfABoundWarnsBeforeTheBoundStopsIt(CapturedOutput output) {
        String key = DurableErasureEvidence.FRONTIER_KEY;
        // 3 of at most 4 pages: past half of the page bound.
        assertThat(bounded(pages(key, 3, null), new GcsObjectRetentionBucket.ListBounds(4, Duration.ofMinutes(5)),
                () -> 0L).versionCount(key)).isEqualTo(3);
        // 1 page in 6 s of a 10 s deadline: past half of the deadline.
        AtomicLong slow = new AtomicLong();
        assertThat(bounded(pages(key, 1, () -> slow.addAndGet(Duration.ofSeconds(6).toNanos())),
                new GcsObjectRetentionBucket.ListBounds(4, Duration.ofSeconds(10)), slow::get).versionCount(key))
                .isEqualTo(1);
        // 2 of 4 pages in 5 s of a 10 s deadline: half of each bound, not past it.
        AtomicLong half = new AtomicLong();
        assertThat(bounded(pages(key, 2, () -> half.addAndGet(Duration.ofMillis(2_500).toNanos())),
                new GcsObjectRetentionBucket.ListBounds(4, Duration.ofSeconds(10)), half::get).versionCount(key))
                .isEqualTo(2);

        assertThat(output.getAll())
                .contains("durable store list " + key + " needed 3 of at most 4 pages and PT0S of its PT5M deadline,"
                        + " past half of a bound")
                .contains("durable store list " + key + " needed 1 of at most 4 pages and PT6S of its PT10S deadline,"
                        + " past half of a bound");
        assertThat(output.getAll().split("past half of a bound", -1)).hasSize(3);
    }

    /**
     * #616 F2: the store reports its history after each frontier read and write, so the warning comes
     * on the normal path, before the fallback read or a recovery listing meets a bound. With one page of
     * 100, the 50th record's frontier write makes the history 51 versions: past half.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theStoreWarnsOnItsNormalPathOnceItsHistoryPassesHalfOfWhatOneListingHolds(CapturedOutput output) {
        GcsObjectRetentionBucket onePage = new GcsObjectRetentionBucket(transport,
                new GcsObjectRetentionBucket.ListBounds(1, Duration.ofSeconds(30)));
        ObjectLockDurableErasureRecordStore small = new ObjectLockDurableErasureRecordStore(onePage, TRUST,
                PRODUCER.keyId(), EvidenceRetentionMode.GOVERNANCE, RETENTION, CLOCK);
        for (int n = 1; n <= 49; n++) {
            assertThat(small.putIfAbsent(record(n)).created()).isTrue();
        }
        assertThat(output.getAll()).doesNotContain("durable store history is about");

        assertThat(small.putIfAbsent(record(50)).created()).isTrue();

        assertThat(output.getAll()).contains("durable store history is about 51 object versions, past half of the"
                + " 100 one listing can hold (page bound 100, deadline bound 30000 at about 100 ms a page)");
        // After a restart a frontier read alone reports it, before any new write.
        GcsObjectRetentionBucket reopened = new GcsObjectRetentionBucket(transport,
                new GcsObjectRetentionBucket.ListBounds(1, Duration.ofSeconds(30)));
        ObjectLockDurableErasureRecordStore restarted = new ObjectLockDurableErasureRecordStore(reopened, TRUST,
                PRODUCER.keyId(), EvidenceRetentionMode.GOVERNANCE, RETENTION, CLOCK);
        assertThat(restarted.findByRequestId(record(1).erasureRequestId())).contains(record(1));
        assertThat(output.getAll().split("durable store history is about 51 object versions", -1)).hasSize(3);
    }

    /**
     * #616 F2: what one listing can hold follows the bounds and the page time this adapter measured,
     * at least 100 ms a page, and the warning repeats at most once an hour while it holds.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theHistoryWarningFollowsTheMeasuredPageTimeAndRepeatsAtMostHourly(CapturedOutput output) {
        String key = DurableErasureEvidence.FRONTIER_KEY;
        AtomicLong clock = new AtomicLong();
        PagingTransport slowPages = pages(key, 1, () -> clock.addAndGet(Duration.ofMillis(200).toNanos()));
        GcsObjectRetentionBucket bucket = bounded(slowPages,
                new GcsObjectRetentionBucket.ListBounds(1_000, Duration.ofSeconds(10)), clock::get);
        // No page measured yet: 100 ms a page, so 10 s holds 100 pages, 10,000 versions.
        bucket.historyEstimate(5_000);
        assertThat(output.getAll()).doesNotContain("durable store history is about");

        // One page took 200 ms: 10 s now holds 50 pages, 5,000 versions.
        assertThat(bucket.versionCount(key)).isEqualTo(1);
        bucket.historyEstimate(2_500);
        assertThat(output.getAll()).doesNotContain("durable store history is about");
        bucket.historyEstimate(2_501);
        bucket.historyEstimate(3_000);
        clock.addAndGet(Duration.ofHours(1).toNanos());
        bucket.historyEstimate(3_000);

        assertThat(output.getAll())
                .contains("durable store history is about 2501 object versions, past half of the 5000 one listing"
                        + " can hold (page bound 100000, deadline bound 5000 at about 200 ms a page)")
                .contains("durable store history is about 3000 object versions");
        assertThat(output.getAll().split("durable store history is about", -1)).hasSize(3);
    }

    private static DurableErasureRecord record(int n) {
        return DurableErasureRecord.of(
                UUID.nameUUIDFromBytes(("gcs-history-request-" + n).getBytes(StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes(("gcs-history-user-" + n).getBytes(StandardCharsets.UTF_8)),
                Instant.parse("2026-10-21T11:00:00Z"));
    }

    /** {@code count} pages of one generation of {@code key} each; {@code perPage} runs before each answer. */
    private static PagingTransport pages(String key, int count, Runnable perPage) {
        return new PagingTransport(request -> {
            if (perPage != null) {
                perPage.run();
            }
            return new GcsObjectRetentionTransport.ListPage(
                    List.of(new GcsObjectRetentionTransport.ListedGeneration(key, request + 1, false)),
                    request < count - 1 ? Optional.of("page-token-" + request) : Optional.empty());
        });
    }

    /** A page the provider never answers: it blocks until its request is abandoned (or 60 s pass). */
    private static GcsObjectRetentionTransport.ListPage stall(CountDownLatch interrupted) {
        try {
            Thread.sleep(Duration.ofSeconds(60));
        } catch (InterruptedException ex) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
        }
        throw new StorageException(0, "the test provider never answered this page");
    }

    private static GcsObjectRetentionBucket bounded(GcsObjectRetentionTransport transport,
                                                    GcsObjectRetentionBucket.ListBounds bounds,
                                                    LongSupplier nanoTime) {
        return new GcsObjectRetentionBucket(transport, duration -> { }, bounds, nanoTime);
    }

    private static GcsObjectRetentionTransport.ListPage pageWithToken(String token) {
        return new GcsObjectRetentionTransport.ListPage(List.of(), Optional.of(token));
    }

    private static GcsObjectRetentionTransport.ListPage lastPage() {
        return new GcsObjectRetentionTransport.ListPage(List.of(), Optional.empty());
    }

    /** Answers listing request n (from 0) with {@code pages.apply(n)} and records its token; nothing else. */
    private static final class PagingTransport implements GcsObjectRetentionTransport {

        /** Thread-safe: since #616 each page is requested on a thread of its own. */
        final List<Optional<String>> requestedTokens = new CopyOnWriteArrayList<>();
        private final IntFunction<ListPage> pages;

        PagingTransport(IntFunction<ListPage> pages) {
            this.pages = pages;
        }

        @Override
        public String bucket() {
            return "parkio-erasure-evidence-test";
        }

        @Override
        public void requireObjectRetentionEnabled() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long createGeneration(String objectName, byte[] bytes, EvidenceRetentionMode mode,
                                     Instant retainUntil, OptionalLong ifGenerationMatch) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<GenerationBytes> liveGeneration(String objectName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RetentionState retention(String objectName, long generation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ListPage listGenerations(String prefix, Optional<String> pageToken, int pageSize) {
            requestedTokens.add(pageToken);
            return pages.apply(requestedTokens.size() - 1);
        }

        @Override
        public byte[] readGeneration(String objectName, long generation) {
            throw new UnsupportedOperationException();
        }
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
                                     Instant retainUntil, OptionalLong ifGenerationMatch) {
            if (attempts.incrementAndGet() <= refusals) {
                throw new StorageException(code, "refused by the test transport");
            }
            return delegate.createGeneration(objectName, bytes, mode, retainUntil, ifGenerationMatch);
        }

        @Override
        public Optional<GenerationBytes> liveGeneration(String objectName) {
            return delegate.liveGeneration(objectName);
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
