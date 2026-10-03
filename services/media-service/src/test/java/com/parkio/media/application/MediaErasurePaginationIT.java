package com.parkio.media.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.application.port.ErasureAckOutbox;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.application.port.MediaStoragePort.StoredVersion;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * U05 bounded listings: every listing call of the storage adapter is one ListObjectVersions
 * request (counted at the relay), whatever else shares the listed prefix, so an attempt's deadline
 * check before each storage call bounds it. Erasure still removes every version across pages, an
 * attempt out of budget in the middle of a listing resumes without skipping anything, and an
 * attempt whose claim went stale between pages never finalizes. Versioned bucket, listing page
 * size 2, real PostgreSQL and MinIO. Uses only APIs that exist before the fix, so the tests run
 * unchanged on both sides.
 */
class MediaErasurePaginationIT extends DelayedObjectWriteITSupport {

    private static final String BUCKET = "parkio-media-pagination-it";
    private static final Duration BUDGET = Duration.ofSeconds(60);
    private static final Duration LEASE = Duration.ofSeconds(120);

    @SpyBean private MediaStoragePort storage;
    @Autowired private MediaErasureJobStore jobs;
    @Autowired private ErasureAckOutbox ackOutbox;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void paginationProperties(DynamicPropertyRegistry registry) {
        registry.add("parkio.media.storage.bucket", () -> BUCKET);
        registry.add("parkio.media.erasure-worker.listing-page-size", () -> "2");
        // Only the tests run attempts here: no scheduled poll of the context's worker.
        registry.add("parkio.media.erasure-worker.enabled", () -> "false");
    }

    @Override
    String bucket() {
        return BUCKET;
    }

    @BeforeAll
    static void versionedBucket() throws Exception {
        MinioClient admin = MediaErasureFixture.minioClient(minioEndpoint(), ACCESS_KEY, SECRET_KEY);
        admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        admin.setBucketVersioning(SetBucketVersioningArgs.builder().bucket(BUCKET)
                .config(new VersioningConfiguration(VersioningConfiguration.Status.ENABLED, null)).build());
    }

    /** No-match: the key is absent and three pages of other keys start with it. */
    @Test
    void anAbsentKeyAmongKeysThatShareItsPrefixTakesOneListingRequest() throws Exception {
        String key = freshKey();
        for (int i = 0; i < 5; i++) {
            putDirect(key + "." + i);
        }
        long before = relay().listings(BUCKET);

        List<StoredVersion> found = storage.versionsOf(BUCKET, key);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(found).as("versions of the absent key").isEmpty();
        softly.assertThat(relay().listings(BUCKET) - before).as("listing requests " + relay().diagnostics())
                .isEqualTo(1);
        softly.assertAll();
    }

    @Test
    void aKeysOwnEntriesComeFromOneListingRequestWhateverFollowsThem() throws Exception {
        String key = freshKey();
        putDirect(key);
        for (int i = 0; i < 5; i++) {
            putDirect(key + "." + i);
        }
        long before = relay().listings(BUCKET);

        List<StoredVersion> found = storage.versionsOf(BUCKET, key);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(found).as("versions of the key").extracting(StoredVersion::objectKey).containsExactly(key);
        softly.assertThat(relay().listings(BUCKET) - before).as("listing requests " + relay().diagnostics())
                .isEqualTo(1);
        softly.assertAll();
    }

    /** Multi-page: five versions of one key, page size 2, other keys after it; each listing is one request. */
    @Test
    void everyVersionOfAKeyIsRemovedAcrossListingsOfOneRequestEach() throws Exception {
        String key = freshKey();
        for (int i = 0; i < 5; i++) {
            putDirect(key);
        }
        for (int i = 0; i < 3; i++) {
            putDirect(key + "." + i);
        }
        long before = relay().listings(BUCKET);
        clearInvocations(storage);

        storage.delete(key);

        long lookups = calls("versionsOf");
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(entriesOf(key)).as("versions of the deleted key").isZero();
        softly.assertThat(entriesUnder(key) - entriesOf(key)).as("versions of the keys that only share its prefix")
                .isEqualTo(3);
        softly.assertThat(lookups).as("lookups of the key").isGreaterThanOrEqualTo(3);
        softly.assertThat(relay().listings(BUCKET) - before).as("listing requests, one per lookup " + relay().diagnostics())
                .isEqualTo(lookups);
        softly.assertAll();
    }

    @Test
    void theNamespaceSweepTakesOneListingRequestPerCallAndErasesEveryPage() throws Exception {
        UUID owner = UUID.randomUUID();
        String namespace = MediaApplicationService.objectKeyPrefix(owner);
        for (int i = 0; i < 5; i++) {
            putDirect(namespace + UUID.randomUUID() + ".png");
        }
        long before = relay().listings(BUCKET);
        clearInvocations(storage);

        UserErasureRequestedEvent event = request(owner);
        handler.handle(event);
        retry(event, 3);

        long listingCalls = calls("versionsUnder") + calls("versionsOf");
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(ackRows(event)).as("media SUCCESS" + diagnostics(event)).isEqualTo(1);
        softly.assertThat(storedVersions(owner)).as("stored versions of the erased user").isEmpty();
        softly.assertThat(calls("versionsUnder")).as("namespace listings (three pages, then empty)")
                .isGreaterThanOrEqualTo(4);
        softly.assertThat(relay().listings(BUCKET) - before).as("listing requests, one per listing call "
                + relay().diagnostics()).isEqualTo(listingCalls);
        softly.assertAll();
    }

    /**
     * Budget exhaustion: each removal takes 25 s of the test clock, so the 60 s budget runs out in
     * the middle of the second page. The attempt stops without SUCCESS; later attempts list afresh
     * and remove everything left, nothing skipped.
     */
    @Test
    void anAttemptOutOfBudgetInTheMiddleOfAListingResumesWithoutSkippingAnything() throws Exception {
        UUID owner = UUID.randomUUID();
        String namespace = MediaApplicationService.objectKeyPrefix(owner);
        for (int i = 0; i < 7; i++) {
            putDirect(namespace + UUID.randomUUID() + ".png");
        }
        MutableClock clock = new MutableClock(Instant.now());
        MediaObjectErasureWorker bounded = boundedWorker(clock);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (((StoredVersion) invocation.getArgument(0)).objectKey().startsWith(namespace)) {
                clock.advance(Duration.ofSeconds(25));
            }
            return null;
        }).when(storage).removeVersion(any());
        UUID job = phaseOneCommitted(owner, clock);

        bounded.process(job);
        int leftAfterFirst = storedVersions(owner).size();
        long acksAfterFirst = acks(job);
        for (int i = 0; i < 20 && jobRows(job) > 0; i++) {
            clock.advance(Duration.ofSeconds(1));
            bounded.process(job);
        }

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(leftAfterFirst).as("versions left after the stopped attempt").isBetween(1, 6);
        softly.assertThat(acksAfterFirst).as("SUCCESS from the stopped attempt").isZero();
        softly.assertThat(acks(job)).as("SUCCESS once everything is gone").isEqualTo(1);
        softly.assertThat(storedVersions(owner)).as("stored versions of the erased user").isEmpty();
        softly.assertThat(jobRows(job)).as("job rows").isZero();
        softly.assertAll();
    }

    /**
     * Stale claim: after the attempt's first page, its lease runs out and another instance's poll
     * claims the job. The stale attempt never finalizes; once the new claim expires too, the poll
     * finishes the job exactly once.
     */
    @Test
    void anAttemptWhoseClaimWentStaleBetweenPagesNeverFinalizes() throws Exception {
        UUID owner = UUID.randomUUID();
        String namespace = MediaApplicationService.objectKeyPrefix(owner);
        for (int i = 0; i < 5; i++) {
            putDirect(namespace + UUID.randomUUID() + ".png");
        }
        MutableClock clock = new MutableClock(Instant.now());
        MediaObjectErasureWorker bounded = boundedWorker(clock);
        AtomicBoolean reclaim = new AtomicBoolean(true);
        AtomicInteger reclaimed = new AtomicInteger();
        doAnswer(invocation -> {
            Object listed = invocation.callRealMethod();
            if (namespace.equals(invocation.getArgument(0)) && reclaim.getAndSet(false)) {
                clock.advance(LEASE.plusSeconds(1));
                Instant now = clock.instant();
                reclaimed.set(jobs.claimDue(20, now, now.plus(LEASE)).size());
            }
            return listed;
        }).when(storage).versionsUnder(anyString());
        UUID job = phaseOneCommitted(owner, clock);

        bounded.process(job);
        long acksFromStale = acks(job);
        long jobsAfterStale = jobRows(job);
        clock.advance(LEASE.plusSeconds(1));
        bounded.processDue();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(reclaimed.get()).as("job reclaimed during the attempt").isGreaterThanOrEqualTo(1);
        softly.assertThat(acksFromStale).as("SUCCESS from the stale attempt").isZero();
        softly.assertThat(jobsAfterStale).as("job still pending for its new claim").isEqualTo(1);
        softly.assertThat(acks(job)).as("SUCCESS from the poll once the new claim expired").isEqualTo(1);
        softly.assertThat(storedVersions(owner)).as("stored versions of the erased user").isEmpty();
        softly.assertThat(jobRows(job)).as("job rows").isZero();
        softly.assertAll();
    }

    private MediaObjectErasureWorker boundedWorker(MutableClock clock) {
        return new MediaObjectErasureWorker(jobs, storage, ackOutbox, transactionManager, clock,
                new SimpleMeterRegistry(), true, 20, LEASE.toMillis(), 5_000, 900_000, BUDGET.toMillis());
    }

    /** Phase 1 as the handler commits it: tombstone and a pending job; no attempt yet. */
    private UUID phaseOneCommitted(UUID owner, MutableClock clock) {
        UUID job = UUID.randomUUID();
        Instant now = clock.instant();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO erased_user_tombstones (auth_user_id, erased_at) VALUES (?, now())", owner);
            jobs.open(job, UUID.randomUUID(), owner, now, now);
        });
        return job;
    }

    private long calls(String method) {
        return mockingDetails(storage).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals(method)).count();
    }

    private long acks(UUID job) {
        return count("SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'AccountErasure' AND event_id = ?", job);
    }

    private long jobRows(UUID job) {
        return count("SELECT COUNT(*) FROM media_erasure_jobs WHERE ack_event_id = ?", job);
    }

    /** Versions and delete markers of exactly {@code key}, read directly from MinIO. */
    private long entriesOf(String key) throws Exception {
        return listed(key).stream().filter(Set.of(key)::contains).count();
    }

    private long entriesUnder(String prefix) throws Exception {
        return listed(prefix).size();
    }

    private List<String> listed(String prefix) throws Exception {
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        for (Result<Item> result : direct.listObjects(ListObjectsArgs.builder().bucket(BUCKET).prefix(prefix)
                .includeVersions(true).recursive(true).build())) {
            keys.add(result.get().objectName());
        }
        return keys;
    }
}
