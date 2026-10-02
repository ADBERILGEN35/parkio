package com.parkio.media.infrastructure.config;

import static org.assertj.core.api.Assertions.catchThrowable;

import com.parkio.media.infrastructure.storage.ScriptedS3Endpoint;
import com.parkio.media.infrastructure.storage.ScriptedS3Endpoint.Reply;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteArgs;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The production storage HTTP client sends an upload's PUT body at most once (U05): no OkHttp
 * follow-up after a reply (503 with {@code Retry-After: 0}, 307/308 with the body, 301/302/303 as a
 * GET, 401, 408) and no second connection. Idempotent calls keep OkHttp's fallback to a host's other
 * addresses. The MinIO SDK and the client are the production ones; a scripted endpoint counts what
 * it received. Uses only APIs that exist before the fix.
 */
class StorageClientSingleTransmissionTest {

    private static final byte[] CONTENT = "single transmission fixture body".getBytes(StandardCharsets.UTF_8);

    @Test
    void aPutAnswered503WithRetryAfter0IsTransmittedOnce() throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(
                Reply.s3Error(503, "SlowDown").with("Retry-After", "0"), Reply.s3Error(403, "AccessDenied"))) {
            put(productionClient(), endpoint.url());

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(endpoint.requestLines()).as("requests received").hasSize(1);
            softly.assertThat(endpoint.bodyBytes()).as("body bytes received").isEqualTo(CONTENT.length);
            softly.assertAll();
        }
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {307, 308})
    void aPutRedirectedWithItsMethodIsTransmittedOnce(int status) throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(
                Reply.redirectToSameTarget(status), Reply.s3Error(403, "AccessDenied"))) {
            put(productionClient(), endpoint.url());

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(endpoint.requestLines()).as("requests received").hasSize(1);
            softly.assertThat(endpoint.bodyBytes()).as("body bytes received").isEqualTo(CONTENT.length);
            softly.assertAll();
        }
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {301, 302, 303})
    void aPutRedirectedToAGetIsNotFollowed(int status) throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(
                Reply.redirectToSameTarget(status), Reply.s3Error(403, "AccessDenied"))) {
            put(productionClient(), endpoint.url());

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(endpoint.requestLines()).as("requests received").hasSize(1);
            softly.assertThat(endpoint.requestLines()).as("requests received").allMatch(line -> line.startsWith("PUT "));
            softly.assertAll();
        }
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {401, 408})
    void aPutAnsweredWithAnAuthenticationOrTimeoutReplyIsNotRetried(int status) throws Exception {
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(
                Reply.s3Error(status, "Scripted"), Reply.s3Error(403, "AccessDenied"))) {
            put(productionClient(), endpoint.url());

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(endpoint.requestLines()).as("requests received").hasSize(1);
            softly.assertAll();
        }
    }

    /** After a reply to the PUT, the client opens no further connection (a refused one would prove nothing). */
    @Test
    void noSecondConnectionIsOpenedForAPutThatWasAnswered() throws Exception {
        ConnectCounter connects = new ConnectCounter();
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(
                Reply.s3Error(503, "SlowDown").with("Retry-After", "0").thenRefuseConnections())) {
            put(productionClient().newBuilder().eventListener(connects).build(), endpoint.url());

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(endpoint.requestLines()).as("requests received").hasSize(1);
            softly.assertThat(connects.starts.get()).as("connections attempted").isEqualTo(1);
            softly.assertAll();
        }
    }

    /** Listings, HEAD and deletes still try a host's next address when one refuses the connection. */
    @Test
    void idempotentCallsStillFallBackToAnotherAddressOfTheHost() throws Exception {
        ConnectCounter connects = new ConnectCounter();
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(Reply.ok())) {
            OkHttpClient client = productionClient().newBuilder().dns(this::refusingThenLoopback).eventListener(connects)
                    .build();
            MinioClient minio = minio(client, "http://storage.test:" + endpoint.port());

            boolean exists = minio.bucketExists(BucketExistsArgs.builder().bucket("bucket").build());

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(exists).as("HEAD answered by the second address").isTrue();
            softly.assertThat(connects.starts.get()).as("connections attempted").isEqualTo(2);
            softly.assertThat(endpoint.requestLines()).as("requests received").hasSize(1);
            softly.assertAll();
        }
    }

    /**
     * For a PUT the MinIO SDK switches OkHttp's recovery off per call (its issue #924), so a host
     * whose first address refuses fails the upload; nothing reaches the store.
     */
    @Test
    void aPutDoesNotTryAnotherAddressAndSendsNothing() throws Exception {
        ConnectCounter connects = new ConnectCounter();
        try (ScriptedS3Endpoint endpoint = new ScriptedS3Endpoint(Reply.ok())) {
            OkHttpClient client = productionClient().newBuilder().dns(this::refusingThenLoopback).eventListener(connects)
                    .build();

            Throwable failure = put(client, "http://storage.test:" + endpoint.port());

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(failure).as("upload failed").isNotNull();
            softly.assertThat(connects.starts.get()).as("connections attempted").isEqualTo(1);
            softly.assertThat(endpoint.requestLines()).as("requests received").isEmpty();
            softly.assertAll();
        }
    }

    private List<InetAddress> refusingThenLoopback(String host) throws java.net.UnknownHostException {
        // 127.0.0.2 is on the loopback interface but nothing listens there: the connection is refused.
        return List.of(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"));
    }

    private static OkHttpClient productionClient() {
        MediaProperties.Storage storage = new MediaProperties.Storage();
        storage.setConnectTimeout(Duration.ofSeconds(2));
        storage.setReadTimeout(Duration.ofSeconds(2));
        storage.setWriteTimeout(Duration.ofSeconds(2));
        storage.setCallTimeout(Duration.ofSeconds(5));
        return MediaInfrastructureConfig.minioHttpClient(storage);
    }

    private static MinioClient minio(OkHttpClient client, String endpoint) {
        return MinioClient.builder().endpoint(endpoint).credentials("parkio-test", "parkio-test-secret")
                .region("us-east-1").httpClient(client).build();
    }

    private static Throwable put(OkHttpClient client, String endpoint) {
        MinioClient minio = minio(client, endpoint);
        return catchThrowable(() -> minio.putObject(PutObjectArgs.builder().bucket("bucket").object("media/owner/key.png")
                .stream(new ByteArrayInputStream(CONTENT), CONTENT.length,
                        Math.max(CONTENT.length, ObjectWriteArgs.MIN_MULTIPART_SIZE))
                .contentType("image/png").build()));
    }

    private static final class ConnectCounter extends EventListener {
        final AtomicInteger starts = new AtomicInteger();

        @Override
        public void connectStart(Call call, java.net.InetSocketAddress address, java.net.Proxy proxy) {
            starts.incrementAndGet();
        }
    }
}
