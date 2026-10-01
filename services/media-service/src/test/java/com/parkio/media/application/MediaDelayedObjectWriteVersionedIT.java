package com.parkio.media.application;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.application.port.MediaFileRepository;
import com.parkio.media.application.result.MediaUploadResult;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketVersioningArgs;
import io.minio.messages.VersioningConfiguration;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * U05 delayed object writes on a versioning-enabled bucket. A delayed PUT would add a version; a
 * delayed key-only DELETE would add a delete marker under the user's key, which names the user.
 * After every delayed request has completed, no version and no delete marker of the erased user
 * may remain next to a media SUCCESS.
 */
class MediaDelayedObjectWriteVersionedIT extends DelayedObjectWriteITSupport {

    private static final String BUCKET = "parkio-media-delayed-write-versioned-it";

    @SpyBean private MediaFileRepository mediaFiles;

    @DynamicPropertySource
    static void bucketProperty(DynamicPropertyRegistry registry) {
        registry.add("parkio.media.storage.bucket", () -> BUCKET);
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

    @Test
    void aDelayedUploadPutLeavesNoVersionNextToMediaSuccess() throws Exception {
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
        softly.assertThat(afterLateWrite).as("the late version exists once the PUT completed").hasSize(1);
        softly.assertThat(ackRows(event)).as("media SUCCESS after the late version was erased").isEqualTo(1);
        softly.assertThat(storedVersions(owner)).as("versions and delete markers of the erased user after SUCCESS")
                .isEmpty();
        softly.assertAll();
    }

    /**
     * The upload's PUT succeeds but its database step fails: the rollback cleanup runs after the
     * transaction (and the owner fence) ended, and its DELETE reaches the store only after SUCCESS.
     */
    @Test
    void aDelayedRollbackCleanupDeleteLeavesNoDeleteMarkerAfterSuccess() throws Exception {
        UUID owner = UUID.randomUUID();
        doThrow(new IllegalStateException("review regression: the database step fails after the PUT"))
                .when(mediaFiles).save(argThat(media -> media != null && media.isOwnedBy(owner)));
        relay().holdNext("DELETE", namespaceFragment(owner));

        Throwable uploadFailure = upload(owner);
        boolean cleanupInFlight = relay().awaitMatched(10);
        UserErasureRequestedEvent event = request(owner);
        handler.handle(event);
        retry(event, 5);
        long acks = ackRows(event);

        String lateReply = relay().release();
        List<String> afterLateDelete = storedVersions(owner);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(uploadFailure).as("upload failed in its database step").isNotNull();
        softly.assertThat(cleanupInFlight).as("the rollback cleanup DELETE was delayed in the network").isTrue();
        softly.assertThat(acks).as("media SUCCESS").isEqualTo(1);
        softly.assertThat(lateReply).as("the store received the delayed DELETE").startsWith("HTTP/1.1");
        softly.assertThat(afterLateDelete).as("versions and delete markers of the erased user after SUCCESS").isEmpty();
        softly.assertAll();
    }

    /** The owner deletes the media; that DELETE reaches the store only after the erasure's SUCCESS. */
    @Test
    void aDelayedOwnerDeleteLeavesNoDeleteMarkerAfterSuccess() throws Exception {
        UUID owner = UUID.randomUUID();
        MediaUploadResult uploaded = uploadOk(owner);
        relay().holdNext("DELETE", namespaceFragment(owner));

        uploads.delete(uploaded.mediaId(), owner);
        boolean deleteInFlight = relay().awaitMatched(10);
        UserErasureRequestedEvent event = request(owner);
        handler.handle(event);
        retry(event, 5);
        long acks = ackRows(event);

        String lateReply = relay().release();
        List<String> afterLateDelete = storedVersions(owner);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(deleteInFlight).as("the owner's DELETE was delayed in the network").isTrue();
        softly.assertThat(acks).as("media SUCCESS").isEqualTo(1);
        softly.assertThat(lateReply).as("the store received the delayed DELETE").startsWith("HTTP/1.1");
        softly.assertThat(afterLateDelete).as("versions and delete markers of the erased user after SUCCESS").isEmpty();
        softly.assertAll();
    }
}
