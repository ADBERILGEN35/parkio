package com.parkio.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.media.application.event.UserErasureRequestedEvent;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * U05 delayed object writes on an unversioned bucket (as Compose creates it). An upload's PUT is
 * delayed in the network past the client's call timeout: the upload fails, its transaction ends and
 * the owner fence is released, but the store applies the PUT later. A media SUCCESS must never be
 * queued while that write could still recreate the erased user's object, and once the delayed
 * request has completed, storage must hold nothing of the user.
 */
class MediaDelayedObjectWriteIT extends DelayedObjectWriteITSupport {

    private static final String BUCKET = "parkio-media-delayed-write-it";

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
    void aDelayedUploadPutNeverCoexistsWithMediaSuccess() throws Exception {
        UUID owner = UUID.randomUUID();
        relay().holdNext("PUT", namespaceFragment(owner));

        Throwable uploadFailure = upload(owner);
        boolean putInFlight = relay().awaitMatched(10);
        UserErasureRequestedEvent event = request(owner);
        handler.handle(event);
        retry(event, 2);
        long acksWhileInFlight = ackRows(event);

        String lateReply = relay().release();
        List<String> afterLateWrite = storedVersions(owner);
        retry(event, 5);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(uploadFailure).as("upload failed at its storage call timeout").isNotNull();
        softly.assertThat(putInFlight).as("the upload's PUT was delayed in the network").isTrue();
        softly.assertThat(acksWhileInFlight).as("media SUCCESS while a PUT of the user could still be applied").isZero();
        softly.assertThat(lateReply).as("the store applied the delayed PUT").contains(" 200 ");
        softly.assertThat(afterLateWrite).as("the late object exists once the PUT completed").hasSize(1);
        softly.assertThat(ackRows(event)).as("media SUCCESS after the late object was erased").isEqualTo(1);
        softly.assertThat(storedVersions(owner)).as("stored versions of the erased user after SUCCESS").isEmpty();
        softly.assertThat(count("SELECT COUNT(*) FROM media_files WHERE owner_user_id = ?", owner)).isZero();
        softly.assertAll();
    }

    /**
     * Client behaviour the ledger relies on: a PUT whose connection breaks after the request was
     * sent is not resent by the client (one PUT request per upload), so observing its object once
     * accounts for it.
     */
    @Test
    void aPutWhoseConnectionBreaksAfterSendingIsSentExactlyOnce() throws Exception {
        UUID owner = UUID.randomUUID();
        relay().dropNext("PUT", namespaceFragment(owner));

        Throwable uploadFailure = upload(owner);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(relay().awaitMatched(10)).as("the PUT reached the relay").isTrue();
        softly.assertThat(uploadFailure).as("upload failed with a broken connection").isNotNull();
        softly.assertThat(relay().count("PUT", namespaceFragment(owner))).as("PUT requests sent for the upload")
                .isEqualTo(1);
        softly.assertAll();
    }
}
