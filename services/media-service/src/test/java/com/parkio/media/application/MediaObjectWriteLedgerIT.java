package com.parkio.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.application.port.ErasureAckOutbox;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.infrastructure.persistence.MediaErasureJobStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * U05 object write ledger ({@code media_object_writes}, V16) on real PostgreSQL and MinIO. Uses the
 * ledger itself, so it has no run before the fix: a write of unknown outcome keeps the erasure
 * pending and observable for as long as it takes, across worker restarts, and is settled only
 * once its object has been observed and removed.
 */
class MediaObjectWriteLedgerIT extends DelayedObjectWriteITSupport {

    private static final String BUCKET = "parkio-media-write-ledger-it";

    @Autowired private MediaErasureJobStore jobs;
    @Autowired private MediaStoragePort storage;
    @Autowired private ErasureAckOutbox ackOutbox;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MeterRegistry meters;

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

    @Test
    void anUploadThatCommitsLeavesNoRecordedWrite() throws Exception {
        UUID owner = UUID.randomUUID();

        uploadOk(owner);

        assertThat(writes(owner)).isZero();
    }

    @Test
    void anUploadWhosePutOutcomeIsUnknownStaysRecordedAsPending() throws Exception {
        UUID owner = UUID.randomUUID();
        relay().holdNext("PUT", namespaceFragment(owner));

        Throwable uploadFailure = upload(owner);
        relay().release();

        assertThat(uploadFailure).isNotNull();
        assertThat(count("SELECT COUNT(*) FROM media_object_writes WHERE owner_user_id = ? AND state = 'PENDING'", owner))
                .isEqualTo(1);
    }

    /**
     * A write recorded just before a crash (or whose reply never came) and whose object never turns
     * up: the erasure stays pending, says why, and is counted; when the store applies it at last,
     * a worker started afresh observes the object, removes it and completes.
     */
    @Test
    void aWriteOfUnknownOutcomeKeepsTheErasurePendingAndObservableUntilItsObjectIsSeen() throws Exception {
        UUID owner = UUID.randomUUID();
        String key = MediaApplicationService.objectKeyPrefix(owner) + UUID.randomUUID() + ".jpg";
        jdbc.update("""
                INSERT INTO media_object_writes (id, owner_user_id, bucket_name, object_key, state, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING', now(), now())
                """, UUID.randomUUID(), owner, BUCKET, key);
        UserErasureRequestedEvent event = request(owner);
        UUID jobId = AccountErasureHandler.ackEventId(event);

        handler.handle(event);
        retry(event, 3);
        long acksWhilePending = ackRows(event);
        long jobsWhilePending = count("SELECT COUNT(*) FROM media_erasure_jobs WHERE ack_event_id = ?", jobId);
        String lastError = jdbc.queryForObject("SELECT last_error FROM media_erasure_jobs WHERE ack_event_id = ?",
                String.class, jobId);
        double unknownGauge = meters.get("parkio.media.object_writes.outcome_unknown").gauge().value();

        byte[] late = png();
        direct.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key)
                .stream(new ByteArrayInputStream(late), late.length, -1).contentType("image/jpeg").build());
        MediaObjectErasureWorker restarted = new MediaObjectErasureWorker(jobs, storage, ackOutbox, transactionManager,
                Clock.systemUTC(), new SimpleMeterRegistry(), false, 20, 120_000, 5_000, 900_000, 60_000,
                Duration.ofSeconds(2));
        for (int i = 0; i < 3 && ackRows(event) == 0; i++) {
            restarted.process(jobId);
        }

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(acksWhilePending).as("SUCCESS while the write's outcome is unknown").isZero();
        softly.assertThat(jobsWhilePending).as("job pending").isEqualTo(1);
        softly.assertThat(lastError).as("recorded reason").contains("1 object write(s) of unknown outcome");
        softly.assertThat(unknownGauge).as("parkio.media.object_writes.outcome_unknown").isGreaterThanOrEqualTo(1.0);
        softly.assertThat(ackRows(event)).as("SUCCESS once the late object was observed and removed").isEqualTo(1);
        softly.assertThat(storedVersions(owner)).as("stored versions after SUCCESS").isEmpty();
        softly.assertThat(writes(owner)).as("recorded writes after SUCCESS").isZero();
        softly.assertAll();
    }

    private long writes(UUID owner) {
        return count("SELECT COUNT(*) FROM media_object_writes WHERE owner_user_id = ?", owner);
    }
}
