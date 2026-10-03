package com.parkio.media.application;

import static org.assertj.core.api.Assertions.catchThrowable;

import com.parkio.media.application.DelayingStorageRelay.Reply;
import com.parkio.media.application.event.UserErasureRequestedEvent;
import com.parkio.media.application.port.MediaStoragePort;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * U05: an upload's PUT is transmitted once, and a reply that does not settle that transmission
 * leaves its write recorded. Production storage client, adapter, upload transaction and erasure
 * worker; real PostgreSQL and MinIO. The relay answers the PUT the way a store or a network path
 * might (503 with {@code Retry-After: 0}, a redirect) while it still holds the request and can
 * deliver it later. A client that sent the body again, or a classifier that judged the write by
 * the reply to a later request, would forget the write and let a media SUCCESS pass while the
 * first transmission can still be applied. Uses only APIs that exist before the fix, so the tests
 * run unchanged on both sides.
 */
class MediaObjectWriteRetryIT extends DelayedObjectWriteITSupport {

    private static final String BUCKET = "parkio-media-write-retry-it";

    @Autowired private MediaStoragePort storage;

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
     * Independent review reproduction {@code finalRejectionAfterResendClearsIntentAndAllowsAckBeforeFirstPutArrives}
     * with correct-behaviour assertions, through the production client instead of a test client.
     */
    @Test
    void aPutAnswered503IsNotResentAndItsWriteBlocksSuccessUntilItsTransmissionLands() throws Exception {
        UUID owner = UUID.randomUUID();
        relay().answerNext("PUT", namespaceFragment(owner), slowDown(), accessDenied());

        Throwable uploadFailure = upload(owner);
        long puts = relay().count("PUT", namespaceFragment(owner));
        long recorded = recordedWrites(owner);
        UserErasureRequestedEvent event = request(owner);
        handler.handle(event);
        retry(event, 2);
        long acksWhileHeld = ackRows(event);

        String lateReply = relay().release();
        List<String> afterLateWrite = storedVersions(owner);
        retry(event, 5);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(uploadFailure).as("upload failed on the 503").isNotNull();
        softly.assertThat(puts).as("PUT transmissions of the upload " + relay().diagnostics()).isEqualTo(1);
        softly.assertThat(recorded).as("the write stays recorded after a reply that does not settle it").isEqualTo(1);
        softly.assertThat(acksWhileHeld).as("media SUCCESS while the first transmission can still be applied")
                .isZero();
        softly.assertThat(lateReply).as("the store applied the first transmission later").contains(" 200 ");
        softly.assertThat(afterLateWrite).as("its object exists once it was applied").hasSize(1);
        softly.assertThat(ackRows(event)).as("media SUCCESS once that object was erased" + diagnostics(event))
                .isEqualTo(1);
        softly.assertThat(storedVersions(owner)).as("stored versions of the erased user after SUCCESS").isEmpty();
        softly.assertThat(recordedWrites(owner)).as("recorded writes of the erased user after SUCCESS").isZero();
        softly.assertAll();
    }

    /**
     * Independent review reproduction {@code http503FollowUpResendsFullPutAndFinal403IsClassifiedDefinitive}
     * with correct-behaviour assertions, through the production client and adapter.
     */
    @Test
    void aPutAnswered503IsTransmittedOnceAndItsOutcomeStaysUnknown() throws Exception {
        String key = freshKey();
        relay().answerNext("PUT", key, slowDown(), accessDenied());
        byte[] content = png();

        Throwable failure = catchThrowable(() -> storage.store(key, content, "image/png"));

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(relay().count("PUT", key)).as("PUT requests " + relay().diagnostics()).isEqualTo(1);
        softly.assertThat(relay().bodyBytes("PUT", key)).as("body bytes transmitted").isEqualTo(content.length);
        softly.assertThat(failure).as("store failed").isNotNull();
        softly.assertThat(failure).as("a 503 does not settle the transmission it answered")
                .isNotInstanceOf(MediaStoragePort.WriteNotAppliedException.class);
        softly.assertAll();
    }

    /** A 307 or 308 keeps the method: following it would send the body a second time. */
    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {307, 308})
    void aRedirectKeepingTheMethodIsNotFollowedWithTheBody(int status) throws Exception {
        String key = freshKey();
        relay().answerNext("PUT", key, Reply.redirectToSameTarget(status), accessDenied());
        byte[] content = png();

        Throwable failure = catchThrowable(() -> storage.store(key, content, "image/png"));

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(relay().count("PUT", key)).as("PUT requests " + relay().diagnostics()).isEqualTo(1);
        softly.assertThat(failure).as("store failed").isNotNull();
        softly.assertThat(failure).as("a redirect does not settle the transmission it answered")
                .isNotInstanceOf(MediaStoragePort.WriteNotAppliedException.class);
        softly.assertAll();
    }

    /** A 301, 302 or 303 turns the PUT into a GET: its reply would say nothing about the PUT. */
    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {301, 302, 303})
    void aRedirectToAGetIsNotFollowed(int status) throws Exception {
        String key = freshKey();
        relay().answerNext("PUT", key, Reply.redirectToSameTarget(status));
        byte[] content = png();

        Throwable failure = catchThrowable(() -> storage.store(key, content, "image/png"));

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(relay().count("PUT", key)).as("PUT requests " + relay().diagnostics()).isEqualTo(1);
        softly.assertThat(relay().count("GET", key)).as("follow-up GET requests").isZero();
        softly.assertThat(failure).as("store failed").isNotNull();
        softly.assertThat(failure).as("a redirect does not settle the transmission it answered")
                .isNotInstanceOf(MediaStoragePort.WriteNotAppliedException.class);
        softly.assertAll();
    }

    /** Positive control: the store's client error on the only transmission settles the write as rejected. */
    @Test
    void aPutRejectedOnItsOnlyTransmissionIsARejection() throws Exception {
        String key = freshKey();
        relay().answerNext("PUT", key, accessDenied());

        Throwable failure = catchThrowable(() -> storage.store(key, png(), "image/png"));

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(relay().count("PUT", key)).as("PUT requests " + relay().diagnostics()).isEqualTo(1);
        softly.assertThat(failure).as("a 403 to the only transmission is a rejection")
                .isInstanceOf(MediaStoragePort.WriteNotAppliedException.class);
        softly.assertAll();
    }

    /** A 408 is not retried for a PUT body (and, as a client error to the only transmission, is a rejection). */
    @Test
    void aPutAnswered408IsNotRetried() throws Exception {
        String key = freshKey();
        relay().answerNext("PUT", key, Reply.s3Error(408, "Request Timeout", "RequestTimeout"), accessDenied());

        Throwable failure = catchThrowable(() -> storage.store(key, png(), "image/png"));

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(relay().count("PUT", key)).as("PUT requests " + relay().diagnostics()).isEqualTo(1);
        softly.assertThat(failure).as("store failed").isNotNull();
        softly.assertAll();
    }

    private static Reply slowDown() {
        return Reply.s3Error(503, "Service Unavailable", "SlowDown").withHeader("Retry-After", "0");
    }

    private static Reply accessDenied() {
        return Reply.s3Error(403, "Forbidden", "AccessDenied");
    }
}
