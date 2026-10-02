package com.parkio.media.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.application.port.ErasureAckOutbox;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.application.port.MediaStoragePort.StoredVersion;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * U05: an object found under the erased user's keys proves that the recorded write of that key
 * was applied, and that evidence is kept durably before the object is removed: for every recorded
 * write of the user (not a first batch), across a failure and a restart between the two steps, on
 * repeated attempts, and without touching another user's writes or objects. Real PostgreSQL and
 * MinIO; the recorded writes are the durable state uploads leave behind (synthetic rows). Uses
 * only APIs that exist before the fix, so the tests run unchanged on both sides.
 */
class MediaObjectWriteObservationIT extends DelayedObjectWriteITSupport {

    private static final String BUCKET = "parkio-media-write-observation-it";

    @SpyBean private MediaStoragePort storage;
    @Autowired private MediaErasureJobStore jobs;
    @Autowired private ErasureAckOutbox ackOutbox;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void bucketProperty(DynamicPropertyRegistry registry) {
        registry.add("parkio.media.storage.bucket", () -> BUCKET);
    }

    @Override
    String bucket() {
        return BUCKET;
    }

    @BeforeEach
    void bucketExists() throws Exception {
        if (!direct.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            direct.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    /**
     * Independent review reproduction {@code namespaceDeletionLosesObservationBeyondFirstHundredIntents}
     * with correct-behaviour assertions: 100 applied writes whose objects are already gone (a crash
     * between removal and settling) come first; the 101st write's object is found and removed.
     */
    @Test
    void anObservedWriteBeyondTheFirstHundredRecordedWritesIsSettled() throws Exception {
        UUID owner = UUID.randomUUID();
        String prefix = MediaApplicationService.objectKeyPrefix(owner);
        Instant earlier = Instant.now().minusSeconds(300);
        for (int i = 0; i < 100; i++) {
            recordWrite(owner, prefix + "applied-" + i, "APPLIED", earlier);
        }
        String key = prefix + "observed-101.png";
        recordWrite(owner, key, "PENDING", Instant.now());
        putDirect(key);

        UserErasureRequestedEvent event = request(owner);
        handler.handle(event);
        retry(event, 4);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(ackRows(event)).as("media SUCCESS" + diagnostics(event)).isEqualTo(1);
        softly.assertThat(storedVersions(owner)).as("stored versions of the erased user").isEmpty();
        softly.assertThat(recordedWrites(owner)).as("recorded writes of the erased user").isZero();
        softly.assertAll();
    }

    @Test
    void everyOneOf150ObservedWritesIsSettled() throws Exception {
        UUID owner = UUID.randomUUID();
        String prefix = MediaApplicationService.objectKeyPrefix(owner);
        Instant at = Instant.now().minusSeconds(600);
        for (int i = 0; i < 150; i++) {
            String key = prefix + UUID.randomUUID() + ".png";
            recordWrite(owner, key, "PENDING", at.plusMillis(i));
            putDirect(key);
        }

        UserErasureRequestedEvent event = request(owner);
        handler.handle(event);
        retry(event, 4);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(ackRows(event)).as("media SUCCESS" + diagnostics(event)).isEqualTo(1);
        softly.assertThat(storedVersions(owner)).as("stored versions of the erased user").isEmpty();
        softly.assertThat(recordedWrites(owner)).as("recorded writes of the erased user").isZero();
        softly.assertAll();
    }

    /**
     * The attempt fails right after the observation, before the object's removal (as a crash
     * would end it); a restarted worker, with nothing in memory, finishes from the durable state.
     */
    @Test
    void aFailureBetweenObservationAndRemovalKeepsTheObservationForARestartedWorker() throws Exception {
        UUID owner = UUID.randomUUID();
        String key = MediaApplicationService.objectKeyPrefix(owner) + UUID.randomUUID() + ".png";
        UUID write = recordWrite(owner, key, "PENDING", Instant.now());
        putDirect(key);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            StoredVersion version = invocation.getArgument(0);
            if (key.equals(version.objectKey()) && failOnce.getAndSet(false)) {
                throw new IllegalStateException("simulated crash between observation and removal");
            }
            return invocation.callRealMethod();
        }).when(storage).removeVersion(any());

        UserErasureRequestedEvent event = request(owner);
        handler.handle(event);
        String stateAfterFailure = writeState(write);
        List<String> objectsAfterFailure = storedVersions(owner);
        long acksAfterFailure = ackRows(event);
        MediaObjectErasureWorker restarted = new MediaObjectErasureWorker(jobs, storage, ackOutbox, transactionManager,
                Clock.systemUTC(), new SimpleMeterRegistry(), false, 20, 120_000, 5_000, 900_000, 60_000);
        for (int i = 0; i < 3 && ackRows(event) == 0; i++) {
            restarted.process(AccountErasureHandler.ackEventId(event));
        }

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(failOnce).as("the removal failed once").isFalse();
        softly.assertThat(stateAfterFailure).as("the observation survived the failed removal").isEqualTo("APPLIED");
        softly.assertThat(objectsAfterFailure).as("the object is still stored after the failure").hasSize(1);
        softly.assertThat(acksAfterFailure).as("media SUCCESS from the failed attempt").isZero();
        softly.assertThat(ackRows(event)).as("media SUCCESS from the restarted worker" + diagnostics(event))
                .isEqualTo(1);
        softly.assertThat(storedVersions(owner)).as("stored versions of the erased user").isEmpty();
        softly.assertThat(recordedWrites(owner)).as("recorded writes of the erased user").isZero();
        softly.assertAll();
    }

    /** Repeated attempts while a write is unresolved neither forget it nor report SUCCESS early. */
    @Test
    void repeatedAttemptsNeitherForgetAnUnresolvedWriteNorReportSuccessEarly() throws Exception {
        UUID owner = UUID.randomUUID();
        String key = MediaApplicationService.objectKeyPrefix(owner) + UUID.randomUUID() + ".png";
        UUID write = recordWrite(owner, key, "PENDING", Instant.now());

        UserErasureRequestedEvent event = request(owner);
        handler.handle(event);
        List<String> statesWhileUnresolved = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            worker.process(AccountErasureHandler.ackEventId(event));
            statesWhileUnresolved.add(writeState(write) + "/acks=" + ackRows(event));
        }
        putDirect(key);
        retry(event, 4);
        long acksAfterLanding = ackRows(event);
        MediaObjectErasureWorker.Outcome again = worker.process(AccountErasureHandler.ackEventId(event));

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(statesWhileUnresolved).as("write state and SUCCESS count over repeated attempts")
                .containsOnly("PENDING/acks=0");
        softly.assertThat(acksAfterLanding).as("media SUCCESS once the write landed and was erased"
                + diagnostics(event)).isEqualTo(1);
        softly.assertThat(again).as("a further attempt after SUCCESS").isEqualTo(MediaObjectErasureWorker.Outcome.NOT_PENDING);
        softly.assertThat(ackRows(event)).as("SUCCESS rows after a further attempt").isEqualTo(1);
        softly.assertThat(storedVersions(owner)).as("stored versions of the erased user").isEmpty();
        softly.assertThat(recordedWrites(owner)).as("recorded writes of the erased user").isZero();
        softly.assertAll();
    }

    @Test
    void anotherUsersRecordedWritesAndObjectsAreUntouched() throws Exception {
        UUID erased = UUID.randomUUID();
        String erasedKey = MediaApplicationService.objectKeyPrefix(erased) + UUID.randomUUID() + ".png";
        recordWrite(erased, erasedKey, "PENDING", Instant.now());
        putDirect(erasedKey);
        UUID other = UUID.randomUUID();
        String otherKey = MediaApplicationService.objectKeyPrefix(other) + UUID.randomUUID() + ".png";
        UUID otherPending = recordWrite(other, otherKey, "PENDING", Instant.now());
        UUID otherApplied = recordWrite(other, MediaApplicationService.objectKeyPrefix(other) + "gone.png", "APPLIED",
                Instant.now());
        putDirect(otherKey);

        UserErasureRequestedEvent event = request(erased);
        handler.handle(event);
        retry(event, 3);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(ackRows(event)).as("media SUCCESS for the erased user" + diagnostics(event)).isEqualTo(1);
        softly.assertThat(storedVersions(erased)).as("stored versions of the erased user").isEmpty();
        softly.assertThat(recordedWrites(erased)).as("recorded writes of the erased user").isZero();
        softly.assertThat(writeState(otherPending)).as("the other user's unresolved write").isEqualTo("PENDING");
        softly.assertThat(writeState(otherApplied)).as("the other user's applied write").isEqualTo("APPLIED");
        softly.assertThat(storedVersions(other)).as("the other user's object").hasSize(1);
        softly.assertAll();
    }
}
