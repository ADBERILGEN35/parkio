package com.parkio.media.infrastructure.storage;

import static org.assertj.core.api.Assertions.catchThrowable;

import com.parkio.media.infrastructure.storage.ScriptedS3Endpoint.Reply;
import io.minio.MinioClient;
import io.minio.ObjectWriteArgs;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

/**
 * Independent review reproductions at the write classifier (U05). A client without the storage
 * client's single-transmission guard (here OkHttp's defaults, as in the review's probe) sends an
 * upload's PUT again after a 503 with {@code Retry-After: 0} or a 307, or replaces it with a GET
 * after a 302. The reply to that later request (a 403, a refused connection) says nothing about
 * the first transmission, which the store may still apply: such a failure must never be reported
 * as certainly not applied. Uses only APIs that exist before the fix.
 */
class MinioResendClassificationTest {

    private static final byte[] CONTENT = "independent review fixture body".getBytes(StandardCharsets.UTF_8);

    /** The review's {@code http503FollowUpResendsFullPutAndFinal403IsClassifiedDefinitive}, correct-behaviour assertion. */
    @Test
    void aFinal403AfterTheClientResentAPutAnswered503IsNotARejection() throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(
                Reply.s3Error(503, "SlowDown").with("Retry-After", "0"), Reply.s3Error(403, "AccessDenied"))) {
            Throwable failure = putWithoutGuard(endpoint);

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(endpoint.requests()).as("precondition: this client sent the PUT twice").isEqualTo(2);
            softly.assertThat(endpoint.bodyBytes()).as("precondition: the full body both times")
                    .isEqualTo(2L * CONTENT.length);
            softly.assertThat(MinioMediaStorageAdapter.definitelyNotApplied((Exception) failure))
                    .as("a 403 to the second transmission settles the first one").isFalse();
            softly.assertAll();
        }
    }

    @Test
    void aRefusedConnectionAfterAPutAnswered503IsNotARejection() throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(
                Reply.s3Error(503, "SlowDown").with("Retry-After", "0").thenRefuseConnections())) {
            Throwable failure = putWithoutGuard(endpoint);

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(endpoint.requests()).as("precondition: one transmission reached the store").isEqualTo(1);
            softly.assertThat(failure).as("precondition: the client then failed to connect")
                    .hasRootCauseInstanceOf(ConnectException.class);
            softly.assertThat(MinioMediaStorageAdapter.definitelyNotApplied((Exception) failure))
                    .as("a refused later connection settles the earlier transmission").isFalse();
            softly.assertAll();
        }
    }

    @Test
    void aReplyToAPutResentAfterA307IsNotARejection() throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(
                Reply.redirectToSameTarget(307), Reply.s3Error(403, "AccessDenied"))) {
            Throwable failure = putWithoutGuard(endpoint);

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(endpoint.requests()).as("precondition: this client sent the PUT twice").isEqualTo(2);
            softly.assertThat(MinioMediaStorageAdapter.definitelyNotApplied((Exception) failure))
                    .as("a 403 to the resent PUT settles the first transmission").isFalse();
            softly.assertAll();
        }
    }

    @Test
    void aReplyToTheGetThatReplacedARedirectedPutIsNotARejection() throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(
                Reply.redirectToSameTarget(302), Reply.s3Error(403, "AccessDenied"))) {
            Throwable failure = putWithoutGuard(endpoint);

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(endpoint.requestLines()).as("precondition: a GET replaced the PUT")
                    .hasSize(2).last().asString().startsWith("GET ");
            softly.assertThat(MinioMediaStorageAdapter.definitelyNotApplied((Exception) failure))
                    .as("a 403 to the GET settles the PUT").isFalse();
            softly.assertAll();
        }
    }

    private static Throwable putWithoutGuard(ScriptedS3Endpoint endpoint) {
        MinioClient client = MinioClient.builder().endpoint(endpoint.url()).credentials("parkio-test", "parkio-test-secret")
                .region("us-east-1").httpClient(new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(5)).build())
                .build();
        return catchThrowable(() -> client.putObject(PutObjectArgs.builder().bucket("bucket").object("media/owner/key.png")
                .stream(new ByteArrayInputStream(CONTENT), CONTENT.length,
                        Math.max(CONTENT.length, ObjectWriteArgs.MIN_MULTIPART_SIZE))
                .contentType("image/png").build()));
    }
}
